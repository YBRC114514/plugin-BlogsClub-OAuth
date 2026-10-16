package io.github.ybrc114514.blogsclub;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.security.authentication.oauth2.HaloOAuth2AuthenticationToken;

/**
 * Persists {@link OAuth2AuthorizedClient}(s) as {@link AuthorizedClient} extensions.
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class DefaultOAuth2AuthorizedClientService implements ReactiveOAuth2AuthorizedClientService {

    private final ReactiveExtensionClient client;
    private final ReactiveClientRegistrationRepository clientRegistrationRepository;

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> Mono<T> loadAuthorizedClient(
        String clientRegistrationId, String principalName) {
        Assert.hasText(clientRegistrationId, "clientRegistrationId cannot be empty");
        Assert.hasText(principalName, "principalName cannot be empty");
        // Resolve the client by its deterministic name instead of a predicate-based list query:
        // ReactiveExtensionClient treats a predicate as an index query and throws when the queried
        // field is not indexed, which surfaced as an opaque HTTP 500.
        return (Mono<T>) client.fetch(BlogsClubAuthorizedClient.class,
                authorizedClientName(clientRegistrationId, principalName))
            .flatMap(authorizedClient -> this.clientRegistrationRepository
                .findByRegistrationId(clientRegistrationId)
                .map(clientRegistration -> new OAuth2AuthorizedClient(clientRegistration,
                    principalName,
                    toAccessToken(authorizedClient),
                    toRefreshToken(authorizedClient))))
            .filter(Objects::nonNull);
    }

    @Override
    public Mono<Void> saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
        Authentication principal) {
        Assert.notNull(authorizedClient, "authorizedClient cannot be null");
        Assert.notNull(principal, "principal cannot be null");
        if (principal instanceof HaloOAuth2AuthenticationToken haloOAuthToken) {
            principal = haloOAuthToken.getOriginal();
        }
        String registrationId = authorizedClient.getClientRegistration().getRegistrationId();
        return client.fetch(BlogsClubAuthorizedClient.class,
                authorizedClientName(registrationId, principal.getName())
            )
            .flatMap(record -> {
                BlogsClubAuthorizedClient newRecord = toAuthorizedClient(authorizedClient);
                newRecord.getMetadata().setVersion(record.getMetadata().getVersion());
                return client.update(newRecord);
            })
            .switchIfEmpty(
                Mono.defer(() -> this.client.create(toAuthorizedClient(authorizedClient)))
            )
            .then();
    }

    @Override
    public Mono<Void> removeAuthorizedClient(String clientRegistrationId, String principalName) {
        Assert.hasText(clientRegistrationId, "clientRegistrationId cannot be empty");
        Assert.hasText(principalName, "principalName cannot be empty");
        return client.fetch(BlogsClubAuthorizedClient.class, authorizedClientName(clientRegistrationId,
                principalName))
            .flatMap(client::delete)
            .then();
    }

    String authorizedClientName(String clientRegistrationId, String principalName) {
        return clientRegistrationId + "-" + principalName;
    }

    OAuth2AccessToken toAccessToken(BlogsClubAuthorizedClient authorizedClient) {
        OAuth2AccessToken.TokenType tokenType;
        if (OAuth2AccessToken.TokenType.BEARER.getValue()
            .equalsIgnoreCase(authorizedClient.getSpec().getAccessTokenType())) {
            tokenType = OAuth2AccessToken.TokenType.BEARER;
        } else {
            throw new IllegalArgumentException(
                "Invalid access token type: " + authorizedClient.getSpec().getAccessTokenType());
        }
        Set<String> scopes = Collections.emptySet();
        String accessTokenScopes = authorizedClient.getSpec().getAccessTokenScopes();
        if (accessTokenScopes != null) {
            scopes = StringUtils.commaDelimitedListToSet(accessTokenScopes);
        }
        return new OAuth2AccessToken(tokenType,
            authorizedClient.getSpec().getAccessTokenValue(),
            authorizedClient.getSpec().getAccessTokenIssuedAt(),
            authorizedClient.getSpec().getAccessTokenExpiresAt(),
            scopes);
    }

    /**
     * Rebuilds the persisted refresh token, or {@code null} when there is none.
     *
     * <p>BlogsClub does not support the refresh token grant, so the stored value is normally absent.
     * {@link OAuth2RefreshToken} asserts on a blank token value in its constructor, which would turn
     * every load into an {@link IllegalArgumentException} — hence the explicit null instead. A record
     * without a spec is treated the same way: there is nothing to refresh, so nothing is returned.</p>
     */
    @Nullable
    OAuth2RefreshToken toRefreshToken(BlogsClubAuthorizedClient authorizedClient) {
        var spec = authorizedClient.getSpec();
        if (spec == null) {
            return null;
        }
        var refreshTokenValue = spec.getRefreshTokenValue();
        if (!StringUtils.hasText(refreshTokenValue)) {
            return null;
        }
        return new OAuth2RefreshToken(refreshTokenValue, spec.getRefreshTokenIssuedAt());
    }

    BlogsClubAuthorizedClient toAuthorizedClient(OAuth2AuthorizedClient param) {
        final String registrationId = param.getClientRegistration().getRegistrationId();
        BlogsClubAuthorizedClient authorizedClient = new BlogsClubAuthorizedClient();
        authorizedClient.setMetadata(new Metadata());
        authorizedClient.getMetadata()
            .setName(authorizedClientName(registrationId, param.getPrincipalName()));

        authorizedClient.setSpec(new BlogsClubAuthorizedClient.BlogsClubAuthorizedClientSpec());
        BlogsClubAuthorizedClient.BlogsClubAuthorizedClientSpec spec = authorizedClient.getSpec();

        spec.setPrincipalName(param.getPrincipalName());
        spec.setRegistrationId(registrationId);
        spec.setAccessTokenType(param.getAccessToken().getTokenType().getValue());
        spec.setAccessTokenValue(param.getAccessToken().getTokenValue());
        spec.setAccessTokenIssuedAt(param.getAccessToken().getIssuedAt());
        spec.setAccessTokenExpiresAt(param.getAccessToken().getExpiresAt());
        spec.setAccessTokenScopes(
            StringUtils.collectionToCommaDelimitedString(param.getAccessToken().getScopes()));

        if (param.getRefreshToken() != null) {
            spec.setRefreshTokenValue(param.getRefreshToken().getTokenValue());
            spec.setRefreshTokenIssuedAt(param.getRefreshToken().getIssuedAt());
        }
        return authorizedClient;
    }
}
