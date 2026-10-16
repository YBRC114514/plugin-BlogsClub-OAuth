package io.github.ybrc114514.blogsclub;

import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;

/**
 * Path predicates telling whether a request actually belongs to the BlogsClub OAuth2 flow.
 *
 * <p>Halo injects plugin web filters through {@code AuthenticationSecurityWebFilter} /
 * {@code HttpBasicSecurityWebFilter}, which wrap the <em>whole</em> request chain — not just the
 * BlogsClub endpoints. Any error handling attached to such a filter therefore also fires for
 * completely unrelated traffic: a request for a missing page raised
 * {@code NoResourceFoundException} (HTTP 404) inside the theme, and the plugin happily replaced
 * the theme's own 404 page with its "BlogsClub 登录失败" page.</p>
 *
 * <p>Every error handler must be guarded by these predicates so that only genuine BlogsClub
 * requests are intercepted; everything else is passed through untouched and keeps Halo's normal
 * error pages.</p>
 *
 * @since 1.0.1
 */
final class BlogsClubRequests {

    /** Authorization kick-off endpoint, e.g. {@code /oauth2/authorization/blogsclub}. */
    private static final String AUTHORIZATION_PREFIX =
        "/oauth2/authorization/" + BlogsClubClientRegistrationRepository.REGISTRATION_ID;

    /** Callback endpoint, e.g. {@code /login/oauth2/code/blogsclub}. */
    private static final String CALLBACK_PREFIX =
        "/login/oauth2/code/" + BlogsClubClientRegistrationRepository.REGISTRATION_ID;

    private BlogsClubRequests() {
    }

    /** Whether the request hits the BlogsClub callback endpoint. */
    static boolean isCallback(ServerWebExchange exchange) {
        return matches(exchange, CALLBACK_PREFIX);
    }

    /** Whether the request hits the BlogsClub authorization kick-off endpoint. */
    static boolean isAuthorization(ServerWebExchange exchange) {
        return matches(exchange, AUTHORIZATION_PREFIX);
    }

    private static boolean matches(ServerWebExchange exchange, String prefix) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return path.value().startsWith(prefix);
    }
}