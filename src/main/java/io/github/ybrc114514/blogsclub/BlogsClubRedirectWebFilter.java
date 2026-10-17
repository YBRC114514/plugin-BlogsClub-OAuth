package io.github.ybrc114514.blogsclub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.web.server.OAuth2AuthorizationRequestRedirectWebFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.HttpBasicSecurityWebFilter;

/**
 * Redirects unauthenticated requests to {@code /oauth2/authorization/blogsclub} to the
 * BlogsClub authorization endpoint. Uses {@link BlogsClubAuthorizationRequestResolver} so that
 * the {@code display=popup} parameter is forwarded for BlogsClub's popup login mode.
 *
 * <p>The delegate is only ever run for the BlogsClub authorization endpoint. Other OAuth2
 * plugins — the official plugin-oauth2 with its github / gitlab / gitee clients — publish
 * their own registrations under the same {@code /oauth2/authorization/{registrationId}} path
 * and wrap the whole chain just like this filter; running the delegate for those paths used
 * to resolve them through our repository and fail them with our error page.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class BlogsClubRedirectWebFilter implements HttpBasicSecurityWebFilter {

    private final WebFilter delegate;

    public BlogsClubRedirectWebFilter(BlogsClubLoginConfiguration configuration) {
        this.delegate = createDelegate(configuration);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Like every {@link HttpBasicSecurityWebFilter} implementation this one wraps the whole
     * request chain, so the fallback page below is only ever rendered for the BlogsClub
     * authorization endpoint. Guarding it keeps Halo's error pages for the rest of the site
     * intact — an unguarded {@code onErrorResume} turned every 404 into this page.</p>
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!BlogsClubRequests.isAuthorization(exchange)) {
            // Not our authorization endpoint — and that includes every other OAuth2 plugin's
            // endpoints: the official plugin-oauth2 serves github / gitlab / gitee under the
            // same /oauth2/authorization/{registrationId} path, and its filters wrap the whole
            // chain just like ours. Running the delegate here would resolve those foreign
            // registration ids through our repository and fail them with our error, taking
            // down every third-party login on the site but BlogsClub's own. Hand them
            // straight to the next filter in the chain.
            return chain.filter(exchange);
        }
        var result = delegate.filter(exchange, chain);
        // An incomplete AppID / AppKey used to surface as a raw HTTP 500 on the very first
        // hop of the login flow. The delegate passes the error through, and this renders a
        // readable page for the BlogsClub authorization endpoint only.
        return result
            .onErrorResume(e -> {
                log.error("[BlogsClub] 发起授权跳转时出错", e);
                var out = exchange.getResponse();
                out.setStatusCode(org.springframework.http.HttpStatus.OK);
                out.getHeaders().setContentType(
                    org.springframework.http.MediaType.TEXT_HTML);
                String html = """
                    <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
                    <title>无法发起 BlogsClub 登录</title></head><body
                    style="font-family:sans-serif;padding:40px;text-align:center">
                    <h1>无法发起 BlogsClub 登录</h1>
                    <p>请检查 AppID / AppKey 配置后重试。</p>
                    <a href="/login">返回登录页</a></body></html>
                    """;
                return out.writeWith(Mono.just(out.bufferFactory().wrap(
                    html.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            });
    }

    private static WebFilter createDelegate(BlogsClubLoginConfiguration configuration) {
        var resolver = new BlogsClubAuthorizationRequestResolver(
            configuration.getClientRegistrationRepository());
        var filter = new OAuth2AuthorizationRequestRedirectWebFilter(resolver);
        filter.setRequestCache(configuration.getRequestCache());
        return filter;
    }
}
