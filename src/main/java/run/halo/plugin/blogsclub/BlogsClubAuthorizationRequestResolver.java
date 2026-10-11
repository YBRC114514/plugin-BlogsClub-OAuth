package run.halo.plugin.blogsclub;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * A {@link DefaultServerOAuth2AuthorizationRequestResolver} that forwards whitelisted query
 * parameters (such as {@code display=popup}) from the original request to the BlogsClub
 * authorization request, so that the popup mode documented by BlogsClub works.
 *
 * @since 1.0.0
 */
public class BlogsClubAuthorizationRequestResolver extends
    DefaultServerOAuth2AuthorizationRequestResolver {

    public static final String DISPLAY = "display";

    public BlogsClubAuthorizationRequestResolver(
        ReactiveClientRegistrationRepository clientRegistrationRepository) {
        super(clientRegistrationRepository);
    }

    @Override
    public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange,
        String clientRegistrationId) {
        return super.resolve(exchange, clientRegistrationId)
            .map(authorizationRequest -> {
                OAuth2AuthorizationRequest.Builder builder =
                    OAuth2AuthorizationRequest.from(authorizationRequest);
                var queryParams = exchange.getRequest()
                    .getQueryParams().toSingleValueMap();
                var display = queryParams.get(DISPLAY);
                if (display != null) {
                    builder.additionalParameters(params -> params.putIfAbsent(DISPLAY, display));
                }
                return builder.build();
            });
    }
}
