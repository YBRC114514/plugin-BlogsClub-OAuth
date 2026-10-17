package io.github.ybrc114514.blogsclub;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginReactiveAuthenticationManager;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.web.server.authentication.OAuth2LoginAuthenticationWebFilter;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.lang.Nullable;
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
import run.halo.app.security.authentication.oauth2.HaloOAuth2AuthenticationToken;

/**
 * BlogsClub OAuth2 authentication web filter. Handles the callback at
 * {@code /login/oauth2/code/blogsclub}, exchanges the authorization code for an access token and
 * loads the BlogsClub profile through {@link BlogsClubOAuth2UserService}.
 *
 * <p>On success the filter does two things in order:</p>
 * <ol>
 *   <li>{@link BlogsClubAccountProvisioningHandler} provisions an account when this BlogsClub
 *       identity is not bound to one yet. Halo's own OAuth2 handling would only drop the visitor into
 *       the manual {@code /login?oauth2_bind} flow, which is not what this plugin advertises.</li>
 *   <li>The request is redirected into the user centre.</li>
 * </ol>
 *
 * <p>Everything after that — mapping the OAuth2 token onto the provisioned account, remember-me,
 * device management and Halo's login bookkeeping — is deliberately <em>not</em> done here. Halo
 * core registers {@code MapOAuth2AuthenticationFilter} for every request; once this filter has
 * saved the {@link OAuth2AuthenticationToken} into the security context (which
 * {@link OAuth2LoginAuthenticationWebFilter} does on success), that core filter resolves the
 * {@code UserConnection} this plugin just wrote, re-maps the authentication onto the real Halo
 * account and invokes Halo's {@code LoginHandlerEnhancer} exactly once with the correct, mapped
 * authentication. Calling the enhancer here as well would fire it twice — and the first time with a
 * raw OAuth2 token whose name is the opaque openid rather than a Halo username, so Halo would
 * record a login event for an account that does not exist.</p>
 *
 * <p><b>Signed-in visitors never get a second account.</b> When the blogger already has an
 * account — the user centre's 绑定 button — the pre-OAuth2 sign-in captured in {@link #filter}
 * is handed to the provisioning handler, which then steps aside entirely: Halo core's
 * {@code MapOAuth2AuthenticationFilter} binds an unbound BlogsClub identity to that very account
 * ({@code createUserConnection} plus session establish), which is the official behaviour.
 * Provisioning here is reserved for anonymous visitors, the "sign in and an account is created
 * for you" case this plugin advertises. The capture is composed with {@code then()}, so the
 * delegate runs exactly once for signed-in and anonymous visitors alike.</p>
 *
 * <p>The filter is registered as a global {@link AuthenticationSecurityWebFilter} and therefore
 * sees every request of the site, so its error handling is restricted to the BlogsClub callback —
 * otherwise it would replace Halo's own 404 and 500 pages.</p>
 *
 * <p><b>Why {@code @Order(0)}.</b> Halo sorts the filters of every plugin for each extension
 * point with {@code AnnotationAwareOrderComparator}; a filter without {@code @Order} runs at
 * {@code Ordered.LOWEST_PRECEDENCE}, and the relative order of same-precedence filters follows
 * plugin load order. Reloading this plugin (every upgrade does) therefore re-appends its
 * filters behind the official plugin-oauth2's, whose callback matcher
 * {@code /login/oauth2/code/{registrationId}} would then claim BlogsClub callbacks and fail
 * them inside its own repository. {@code @Order(0)} keeps this filter ahead of any unordered
 * plugin filter regardless of load order, and the literal-path matcher below then leaves
 * foreign callbacks to their own plugin's filter.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@Order(0)
public class BlogsClubAuthenticationWebFilter implements AuthenticationSecurityWebFilter {

    private final WebFilter delegate;

    private final ServerSecurityContextRepository securityContextRepository;

    public BlogsClubAuthenticationWebFilter(BlogsClubLoginConfiguration configuration,
        ServerSecurityContextRepository securityContextRepository,
        OAuth2Properties oAuth2Properties,
        BlogsClubAccountProvisioningHandler provisioningHandler) {
        this(configuration, securityContextRepository, oAuth2Properties, provisioningHandler, null);
    }

    /**
     * Package-private constructor for tests: the last parameter replaces the internally built
     * {@link OAuth2LoginAuthenticationWebFilter} delegate, so a test can count how often the
     * delegate is subscribed. The delegate is a {@code Mono<Void>} and therefore always
     * completes <em>empty</em>; composing it with {@code switchIfEmpty} subscribed it twice for
     * signed-in visitors — production incident 2026-10-17 (v1.4.0): the bind itself succeeded,
     * but the second attempt failed with {@code authorization_request_not_found} and its error
     * page overwrote the successful redirect.
     */
    BlogsClubAuthenticationWebFilter(BlogsClubLoginConfiguration configuration,
        ServerSecurityContextRepository securityContextRepository,
        OAuth2Properties oAuth2Properties,
        BlogsClubAccountProvisioningHandler provisioningHandler,
        @Nullable WebFilter delegateOverride) {
        this.securityContextRepository = securityContextRepository;

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
        // The authentication result type must be unwrapped defensively: depending on where this
        // manager sits in the chain the result is either a plain OAuth2AuthenticationToken or
        // Halo's HaloOAuth2AuthenticationToken wrapper (which keeps the original in
        // getOriginal()).
        org.springframework.security.authentication.ReactiveAuthenticationManager authManager =
            authentication -> tokenEndpoint.authenticate(authentication);

        var filter = new OAuth2LoginAuthenticationWebFilter(
            authManager, configuration.getAuthorizedClientRepository());
        filter.setRequiresAuthenticationMatcher(configuration.getAuthenticationMatcher());
        var converter = new org.springframework.security.oauth2.client.web.server
            .ServerOAuth2AuthorizationCodeAuthenticationTokenConverter(
            configuration.getClientRegistrationRepository());
        var successHandler = new org.springframework.security.web.server.authentication
            .RedirectServerAuthenticationSuccessHandler("/uc");
        successHandler.setRequestCache(configuration.getRequestCache());
        // Account provisioning has to run before the redirect is issued: an unbound BlogsClub
        // identity needs a User to be bound to, and it is this filter — not Halo's OAuth2 chain —
        // that can create one.
        //
        // No LoginHandlerEnhancer call here on purpose: Halo core's MapOAuth2AuthenticationFilter
        // runs after this chain completes, finds the UserConnection this filter just wrote, maps
        // the authentication onto the real account and then calls the enhancer once with the
        // mapped authentication. When no account could be provisioned (e.g. the site closed
        // registration), that same core filter logs the visitor out and sends them to
        // /login?oauth2_bind — Halo's native binding flow — which is exactly the documented
        // fallback behaviour.
        //
        // The pre-authentication captured in filter() decides between the two branches: a
        // visitor who already has an account (the user centre's "bind" button) must NOT get a
        // second one — Halo core binds the identity to the signed-in account instead — while an
        // anonymous visitor still gets the account this plugin advertises.
        filter.setAuthenticationSuccessHandler((webFilterExchange, authentication) -> {
            var preAuthentication = (Authentication) webFilterExchange.getExchange()
                .getAttributes().get(PRE_AUTHENTICATION_ATTRIBUTE);
            return provisioningHandler.provisionIfUnbound(authentication, preAuthentication)
                .then(successHandler.onAuthenticationSuccess(webFilterExchange, authentication));
        });
        filter.setAuthenticationFailureHandler(
            new org.springframework.security.web.server.authentication
                .RedirectServerAuthenticationFailureHandler("/login?blogsclub_error"));
        filter.setServerAuthenticationConverter(converter);
        filter.setSecurityContextRepository(securityContextRepository);

        this.delegate = delegateOverride != null ? delegateOverride : filter;
    }

    /**
     * Extracts the OAuth2 authentication result from whatever wrapper the chain produced.
     *
     * <p>Returns {@code null} for a non-OAuth2 authentication so a caller never writes BlogsClub
     * attributes onto an identity that did not come from BlogsClub.</p>
     */
    @Nullable
    private static OAuth2AuthenticationToken unwrapOAuth2Token(Authentication authenticated) {
        if (authenticated instanceof HaloOAuth2AuthenticationToken haloToken) {
            return haloToken.getOriginal();
        }
        if (authenticated instanceof OAuth2AuthenticationToken token) {
            return token;
        }
        return null;
    }

    /**
     * Exchange attribute under which the visitor's pre-OAuth2 sign-in is stashed while the
     * delegate runs. Halo core uses the same idea — its {@code MapOAuth2AuthenticationFilter}
     * caches the pre-authentication in an exchange attribute before the chain continues and
     * binds an unbound identity to exactly that account — so this attribute is what makes the
     * plugin honour the official "bind to my account" behaviour instead of shadowing it.
     */
    static final String PRE_AUTHENTICATION_ATTRIBUTE =
        BlogsClubAuthenticationWebFilter.class.getName() + ".PRE_AUTHENTICATION";

    /**
     * Same trust resolver core applies to its pre-authentication: anonymous and remember-me
     * tokens report {@code isAuthenticated() == true} but are not sign-ins.
     */
    private static final org.springframework.security.authentication.AuthenticationTrustResolver
        TRUST_RESOLVER = new org.springframework.security.authentication
        .AuthenticationTrustResolverImpl();

    /**
     * {@inheritDoc}
     *
     * <p>This filter is registered as a global {@link AuthenticationSecurityWebFilter}, so it sees
     * every single request of the site. The error handling below must therefore be restricted to
     * the BlogsClub callback: swallowing errors unconditionally replaced Halo's own 404 page (and
     * any other error page) with the "BlogsClub 登录失败" page for the whole blog.</p>
     *
     * <p>For the callback the visitor's current sign-in is captured <em>before</em> the delegate
     * runs: a successful OAuth2 login overwrites the security context with the raw OAuth2 token,
     * and without the capture a signed-in blogger binding BlogsClub would be indistinguishable
     * from an anonymous one.</p>
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!BlogsClubRequests.isCallback(exchange)) {
            // Not ours: let the error travel up untouched so Halo / the theme renders its own
            // 404 or 500 page.
            return delegate.filter(exchange, chain);
        }
        // ReactorContextWebFilter has already run (it precedes AUTHENTICATION in the security
        // chain), so the repository load below returns the session's sign-in for a signed-in
        // visitor and completes empty for an anonymous one. An OAuth2 token itself is not a
        // sign-in here — mirroring how core's PRE_AUTHENTICATION excludes it.
        //
        // Compose with then(), never with flatMap(...)+switchIfEmpty(...): the delegate is a
        // Mono<Void> and therefore always completes *empty*, so a switchIfEmpty downstream of
        // it fires for every signed-in visitor and subscribes the delegate a second time — by
        // which point the authorization request has already been removed from the session, so
        // the second attempt fails with authorization_request_not_found and its error page
        // overwrites the successful first run's redirect. That is exactly what v1.4.0 shipped:
        // the bind succeeded server-side, yet the blogger saw "BlogsClub 登录失败
        // [authorization_request_not_found]". then() subscribes the delegate exactly once,
        // whether the capture found a sign-in or not.
        return capturePreAuthentication(exchange)
            .then(delegate.filter(exchange, chain))
            .onErrorResume(BlogsClubConfigException.class, e -> notice(exchange,
                "尚未完成 BlogsClub 配置", NOTICE))
            .onErrorResume(e -> {
                log.error("[BlogsClub] 处理 BlogsClub 回调时出错", e);
                return error(exchange, "BlogsClub 登录失败",
                    e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            });
    }

    /**
     * Stashes the visitor's current sign-in — if any — into {@link #PRE_AUTHENTICATION_ATTRIBUTE}
     * before the delegate runs. A successful OAuth2 login overwrites the security context with
     * the raw OAuth2 token, so without this capture a signed-in blogger binding BlogsClub would
     * be indistinguishable from an anonymous one.
     *
     * <p>Completes empty for anonymous visitors, which is exactly what the {@code then()} in
     * {@link #filter} needs: the delegate runs exactly once in either case.</p>
     */
    private Mono<Authentication> capturePreAuthentication(ServerWebExchange exchange) {
        return securityContextRepository.load(exchange)
            .mapNotNull(SecurityContext::getAuthentication)
            .filter(authentication -> TRUST_RESOLVER.isAuthenticated(authentication))
            .filter(authentication -> !(authentication instanceof OAuth2AuthenticationToken))
            .doOnNext(authentication ->
                exchange.getAttributes().put(PRE_AUTHENTICATION_ATTRIBUTE, authentication));
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
