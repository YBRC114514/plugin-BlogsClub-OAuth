package run.halo.plugin.blogsclub;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.web.server.authentication.OAuth2LoginAuthenticationWebFilter;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import run.halo.app.security.AuthenticationSecurityWebFilter;

/**
 * BlogsClub OAuth2 authentication web filter. Handles the callback at
 * {@code /login/oauth2/code/blogsclub}, exchanges the authorization code for an access token and
 * loads the BlogsClub profile through {@link BlogsClubOAuth2UserService}.
 *
 * <p>The account itself is created and bound by Halo's own OAuth2 handling. This filter only makes
 * sure the BlogsClub profile is available to {@link BlogsClubUserPreCreatingHandler} so the new
 * account is created with the e-mail BlogsClub already verified, sparing the blogger a manual
 * e-mail verification step.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class BlogsClubAuthenticationWebFilter implements AuthenticationSecurityWebFilter {

    private final WebFilter delegate;

    private final BlogsClubUserPreCreatingHandler profileHandler;

    public BlogsClubAuthenticationWebFilter(BlogsClubLoginConfiguration configuration,
        ServerSecurityContextRepository securityContextRepository,
        OAuth2Properties oAuth2Properties,
        BlogsClubUserPreCreatingHandler profileHandler) {

        var proxy = oAuth2Properties.getProxy();
        if (proxy.isEnabled()) {
            // Validate the user-controlled outbound proxy destination before attaching any
            // credential. Rejects scheme/whitespace tricks that could redirect traffic.
            validateProxyHost(proxy.getHost());
        }
        var client = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
            .responseTimeout(Duration.ofSeconds(15));
        if (proxy.isEnabled()) {
            client = client.proxy(typeSpec -> {
                    var builder = typeSpec.type(ProxyProvider.Proxy.HTTP)
                        .host(proxy.getHost())
                        .port(proxy.getPort());
                    if (StringUtils.hasText(proxy.getUsername())) {
                        builder.username(proxy.getUsername())
                            .password(u -> proxy.getPassword());
                    }
                    if (proxy.getConnectTimeoutMillis() != null
                        && proxy.getConnectTimeoutMillis() > 0) {
                        builder.connectTimeoutMillis(
                            proxy.getConnectTimeoutMillis());
                    }
                }
            );
        }
        // Cap the in-memory response body to avoid unbounded buffering from the upstream.
        var exchangeStrategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
            .build();
        var webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(client))
            .exchangeStrategies(exchangeStrategies)
            .build();

        var accessTokenResponseClient = new WebClientReactiveAuthorizationCodeTokenResponseClient();
        accessTokenResponseClient.setWebClient(webClient);

        // Use the BlogsClub-aware user service to unwrap the non-standard userinfo envelope.
        var tokenEndpoint = new OAuth2LoginReactiveAuthenticationManager(
            accessTokenResponseClient,
            new BlogsClubOAuth2UserService(webClient)
        );
        // Park the profile right after the token/userinfo exchange. This is the last point before
        // Halo provisions the account and calls UserPreCreatingHandler, so the BlogsClub e-mail is
        // available in time to be written onto the new account.
        org.springframework.security.authentication.ReactiveAuthenticationManager authManager =
            authentication -> tokenEndpoint.authenticate(authentication)
                .doOnNext(authentication1 -> {
                    if (authentication1 instanceof OAuth2AuthenticationToken token) {
                        profileHandler.remember(
                            token.getAuthorizedClientRegistrationId(), token.getPrincipal());
                    }
                });

        var filter = new OAuth2LoginAuthenticationWebFilter(
            authManager, configuration.getAuthorizedClientRepository());
        filter.setRequiresAuthenticationMatcher(configuration.getAuthenticationMatcher());
        var converter = new org.springframework.security.oauth2.client.web.server
            .ServerOAuth2AuthorizationCodeAuthenticationTokenConverter(
                configuration.getClientRegistrationRepository());
        var successHandler = new org.springframework.security.web.server.authentication
            .RedirectServerAuthenticationSuccessHandler("/uc");
        successHandler.setRequestCache(configuration.getRequestCache());
        filter.setAuthenticationSuccessHandler(successHandler);
        filter.setAuthenticationFailureHandler(
            new org.springframework.security.web.server.authentication
                .RedirectServerAuthenticationFailureHandler("/login?blogsclub_error"));
        filter.setServerAuthenticationConverter(converter);
        filter.setSecurityContextRepository(securityContextRepository);

        this.delegate = filter;
        this.profileHandler = profileHandler;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This filter is registered as a global {@link AuthenticationSecurityWebFilter}, so it sees
     * every single request of the site. The error handling below must therefore be restricted to
     * the BlogsClub callback: swallowing errors unconditionally replaced Halo's own 404 page (and
     * any other error page) with the "BlogsClub 登录失败" page for the whole blog.</p>
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var result = delegate.filter(exchange, chain);
        if (!BlogsClubRequests.isCallback(exchange)) {
            // Not ours: let the error travel up untouched so Halo / the theme renders its own
            // 404 or 500 page.
            return result;
        }
        return result
            .onErrorResume(BlogsClubConfigException.class, e -> notice(exchange,
                "尚未完成 BlogsClub 配置", NOTICE))
            .onErrorResume(e -> {
                log.error("[BlogsClub] 处理 BlogsClub 回调时出错", e);
                return error(exchange, "BlogsClub 登录失败",
                    e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            });
    }

    private static final String NOTICE = """
        请在 Halo 后台进入 <b>用户 → 认证方式</b>，找到「BlogsClub 账号验证」，
        点击<b>设置</b>，填写 <b>AppID</b> 与 <b>AppKey</b> 并保存后重试。<br>
        （AppID / AppKey 在 BlogsClub 开放平台的应用详情页获取）
        """;

    /**
     * Minimal inline page used when the plugin cannot continue the flow. Kept dependency free on
     * purpose so a failure in the OAuth2 chain still produces a readable message.
     */
    private static Mono<Void> notice(ServerWebExchange exchange, String title, String body) {
        return page(exchange, title, body);
    }

    private static Mono<Void> error(ServerWebExchange exchange, String title, String message) {
        return page(exchange, title, escape(message));
    }

    private static Mono<Void> page(ServerWebExchange exchange, String title, String bodyHtml) {
        var response = exchange.getResponse();
        response.setStatusCode(org.springframework.http.HttpStatus.OK);
        response.getHeaders().setContentType(org.springframework.http.MediaType.TEXT_HTML);
        var html = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title></head>
            <body style="margin:0;min-height:100vh;display:flex;align-items:center;
            justify-content:center;background:#f4f6fb;color:#1f2937;
            font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif">
            <div style="max-width:420px;background:#fff;border:1px solid #e5e7eb;border-radius:14px;
            padding:28px;text-align:center;box-shadow:0 8px 28px rgba(15,23,42,.08)">
            <h1 style="font-size:20px;margin:0 0 12px;color:#b45309">%s</h1>
            <p style="color:#6b7280;font-size:13px;line-height:1.7">%s</p>
            <a href="/login" style="display:block;margin-top:20px;padding:11px 14px;border-radius:8px;
            background:#2563eb;color:#fff;text-decoration:none;font-size:15px;font-weight:600">返回登录页</a>
            </div></body></html>
            """.formatted(escape(title), escape(title), bodyHtml);
        return response.writeWith(Mono.just(
            response.bufferFactory().wrap(html.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static String escape(String value) {
        return value == null ? "" : org.springframework.web.util.HtmlUtils.htmlEscape(value);
    }

    /**
     * Validates the proxy host before it is used as an outbound destination. Only plain
     * hostnames/IPs are accepted; any scheme, user-info, path or whitespace is rejected so a
     * misconfigured proxy cannot be abused to tunnel credentials to an unexpected target.
     */
    private void validateProxyHost(String host) {
        if (!StringUtils.hasText(host)
            || host.contains("/")
            || host.contains("@")
            || host.contains(":")
            || host.matches(".*\\s.*")) {
            throw new IllegalArgumentException(
                "Invalid proxy host '" + host + "': expected a bare hostname or IP without scheme");
        }
    }
}