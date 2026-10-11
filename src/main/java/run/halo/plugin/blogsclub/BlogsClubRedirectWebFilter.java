package run.halo.plugin.blogsclub;

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
 * @since 1.0.0
 */
@Slf4j
@Component
public class BlogsClubRedirectWebFilter implements HttpBasicSecurityWebFilter {

    private final WebFilter delegate;

    public BlogsClubRedirectWebFilter(BlogsClubLoginConfiguration configuration) {
        this.delegate = createDelegate(configuration);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return delegate.filter(exchange, chain)
            // An incomplete AppID / AppKey used to surface as a raw HTTP 500 on the very first
            // hop of the login flow. Let the delegate pass it through; the OAuth2 callback filter
            // renders a readable page for the same condition.
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
