package io.github.ybrc114514.blogsclub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive OAuth2 user service for BlogsClub.
 *
 * <p>BlogsClub's userinfo endpoint returns a non-standard envelope:
 * <pre>
 * {
 *   "status": 200,
 *   "msg": "ok",
 *   "data": {
 *     "openid": "123",
 *     "blog_name": "某某某的自留地",
 *     "blogger_avatar": "https://www.blogsclub.org/blog/xxx/avatar.png",
 *     "verified": 1,
 *     "blog_url": "https://example.com",
 *     "email": "moumoumou@example.com"
 *   }
 * }
 * </pre>
 * This service unwraps the {@code data} object, exposes it as the OAuth2 user attributes
 * and uses {@code openid} as the name attribute, so that the downstream
 * {@link org.springframework.security.oauth2.client.authentication.OAuth2LoginReactiveAuthenticationManager}
 * can build a standard {@link OAuth2User} for Halo to bind the identity.
 * </p>
 *
 * @since 1.0.0
 */
@Slf4j
public class BlogsClubOAuth2UserService implements ReactiveOAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final WebClient webClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BlogsClubOAuth2UserService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<OAuth2User> loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // The URI configured on the ClientRegistration is authoritative. BlogsClub answers userinfo
        // on the same /Oauth/getOauthData endpoint as the token exchange, told apart by the "action"
        // query parameter that the registration already carries.
        var userInfoUri = userRequest.getClientRegistration()
            .getProviderDetails()
            .getUserInfoEndpoint()
            .getUri();
        var accessToken = userRequest.getAccessToken().getTokenValue();

        return webClient.get()
            // No access_token in the query string: URLs end up in reverse-proxy and gateway access
            // logs, so the credential is carried in the Authorization header only. This matches
            // userInfoAuthenticationMethod: header in client-registrations.yaml.
            .uri(URI.create(userInfoUri))
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
            .map(this::parse)
            .onErrorMap(e -> !(e instanceof OAuth2AuthenticationException),
                e -> new OAuth2AuthenticationException(
                    "Failed to fetch BlogsClub user profile: " + e.getMessage()));
    }

    private OAuth2User parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int status = root.path("status").asInt(200);
            if (status != 200) {
                throw new OAuth2AuthenticationException(
                    "BlogsClub returned status " + status + ": " + root.path("msg").asText());
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isEmpty()) {
                throw new OAuth2AuthenticationException("BlogsClub userinfo missing 'data'");
            }

            Map<String, Object> attributes = new java.util.HashMap<>();
            Iterator<String> fieldNames = data.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                attributes.put(fieldName, data.get(fieldName).asText());
            }

            String openid = data.path("openid").asText();
            if (!org.springframework.util.StringUtils.hasText(openid)) {
                throw new OAuth2AuthenticationException("BlogsClub userinfo missing 'openid'");
            }

            Set<GrantedAuthority> authorities = new HashSet<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            attributes.put("name", openid);
            return new DefaultOAuth2User(authorities, attributes, "openid");
        } catch (OAuth2AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuth2AuthenticationException(
                "Failed to parse BlogsClub userinfo: " + (e.getMessage() != null
                    ? e.getMessage() : e.toString()));
        }
    }
}
