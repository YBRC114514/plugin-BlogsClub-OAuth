package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.AuthProvider;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.security.AuthenticationSecurityWebFilter;
import run.halo.app.security.HttpBasicSecurityWebFilter;
import run.halo.app.security.LoginHandlerEnhancer;

/**
 * Coexistence with other OAuth2 plugins — above all the official plugin-oauth2, whose
 * github / gitlab / gitee clients share the {@code /oauth2/authorization/{registrationId}}
 * and {@code /login/oauth2/code/{registrationId}} endpoints, and whose filters wrap the
 * whole request chain exactly like ours.
 *
 * <p>Production incident 2026-10-17: with both plugins enabled, starting a github login (for
 * example to bind an existing Halo account) failed with {@code "Oauth2 client registration
 * github not found"} — thrown by {@link BlogsClubClientRegistrationRepository} after our
 * broad-path filter had claimed the foreign registration. Every third-party login on the
 * site was down except BlogsClub's own. These tests pin the deferral behaviour that fixes
 * it: never claim, never fail, another plugin's registration.</p>
 *
 * <p>A second incident the same day showed the flip side of the same mechanism: after
 * upgrading our plugin its filters were re-appended <em>behind</em> the official plugin's
 * (neither side carried an {@code @Order}, so plugin load order decided), and the official
 * broad-path filter then claimed even BlogsClub's own endpoints and failed them inside its
 * strict client-conf parser — every OAuth2 login on the site returned 500. The
 * {@code @Order(0)} on our filters pins the precedence: ours always run first, and the path
 * guards under test here hand foreign requests downstream.</p>
 */
class BlogsClubCoexistenceTest {

    private ReactiveExtensionClient client;

    private BlogsClubClientRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        repository = new BlogsClubClientRegistrationRepository(client,
            mock(ExternalUrlSupplier.class));
    }

    @Test
    @DisplayName("其他插件的 registration id 原样放行：返回空且不触碰扩展端")
    void defersForeignRegistrationIdsToOtherPlugins() {
        StepVerifier.create(repository.findByRegistrationId("github"))
            .verifyComplete();
        StepVerifier.create(repository.findByRegistrationId("gitee"))
            .verifyComplete();
        StepVerifier.create(repository.findByRegistrationId(null))
            .verifyComplete();

        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("BlogsClub 自己的 id 仍然走完整解析链路")
    void stillResolvesOwnRegistrationId() {
        when(client.fetch(AuthProvider.class, "blogsclub")).thenReturn(Mono.empty());

        // Our own id must still reach the extension client and fail loudly when the
        // auth provider is missing — passing it through would silently break our login.
        StepVerifier.create(repository.findByRegistrationId("blogsclub"))
            .verifyError(ProviderNotFoundException.class);
        verify(client).fetch(AuthProvider.class, "blogsclub");
    }

    @Test
    @DisplayName("重定向过滤器对其他插件的授权路径直接放行")
    void redirectFilterPassesForeignAuthorizationPathsThrough() {
        var filter = redirectFilter();

        var exchange = exchange("/oauth2/authorization/github");
        var chain = chain(exchange);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("重定向过滤器对自己的授权路径仍然给出可读错误页")
    void redirectFilterStillHandlesOwnAuthorizationPath() {
        var clientRegistrationRepository = mock(ReactiveClientRegistrationRepository.class);
        when(clientRegistrationRepository.findByRegistrationId("blogsclub"))
            .thenReturn(Mono.error(new ProviderNotFoundException("test")));
        var configuration = mock(BlogsClubLoginConfiguration.class);
        when(configuration.getClientRegistrationRepository())
            .thenReturn(clientRegistrationRepository);
        when(configuration.getRequestCache())
            .thenReturn(new WebSessionServerRequestCache());
        var filter = new BlogsClubRedirectWebFilter(configuration);

        var exchange = exchange("/oauth2/authorization/blogsclub");
        var chain = chain(exchange);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // The delegate ran for our own path and its failure was turned into the readable
        // "cannot start BlogsClub login" page instead of a raw 500.
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType())
            .isEqualTo(MediaType.TEXT_HTML);
    }

    @Test
    @DisplayName("回调匹配器只认 BlogsClub 自己的回调路径")
    void callbackMatcherOnlyMatchesOwnCallbackPath() {
        var configuration = new BlogsClubLoginConfiguration(client,
            mock(LoginHandlerEnhancer.class), mock(ExternalUrlSupplier.class));

        StepVerifier.create(configuration.getAuthenticationMatcher()
            .matches(exchange("/login/oauth2/code/blogsclub?code=c&state=s")))
            .assertNext(result -> assertThat(result.isMatch()).isTrue())
            .verifyComplete();
        StepVerifier.create(configuration.getAuthenticationMatcher()
            .matches(exchange("/login/oauth2/code/github?code=c&state=s")))
            .assertNext(result -> assertThat(result.isMatch()).isFalse())
            .verifyComplete();
    }

    @Test
    @DisplayName("回调过滤器在委托前把已登录访客的登录态存入 exchange 属性")
    void callbackFilterStashesPreAuthenticationForSignedInVisitor() {
        var signedIn = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken("user-existing", "n/a",
            java.util.List.of(() -> "ROLE_USER"));
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(
            Mono.just(new org.springframework.security.core.context.SecurityContextImpl(signedIn)));
        var filter = authenticationFilter(securityContextRepository);

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        // The delegate fails the fabricated callback (no saved authorization request), which is
        // fine: the capture under test happens before it.
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(exchange.getAttributes())
            .containsEntry(BlogsClubAuthenticationWebFilter.PRE_AUTHENTICATION_ATTRIBUTE, signedIn);
    }

    @Test
    @DisplayName("匿名访客的回调不写入预登录态（保留首次登录建号行为）")
    void callbackFilterLeavesPreAuthenticationEmptyForAnonymousVisitor() {
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(Mono.empty());
        var filter = authenticationFilter(securityContextRepository);

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(exchange.getAttributes())
            .doesNotContainKey(BlogsClubAuthenticationWebFilter.PRE_AUTHENTICATION_ATTRIBUTE);
    }

    @Test
    @DisplayName("匿名令牌不算已登录，回调不写入预登录态")
    void callbackFilterIgnoresAnonymousPreAuthentication() {
        var anonymous = new org.springframework.security.authentication
            .AnonymousAuthenticationToken("key", "anonymousUser",
            java.util.List.of(() -> "ROLE_ANONYMOUS"));
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(
            Mono.just(new org.springframework.security.core.context.SecurityContextImpl(anonymous)));
        var filter = authenticationFilter(securityContextRepository);

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(exchange.getAttributes())
            .doesNotContainKey(BlogsClubAuthenticationWebFilter.PRE_AUTHENTICATION_ATTRIBUTE);
    }

    @Test
    @DisplayName("会话里残留的 OAuth2 令牌不算已登录，回调不写入预登录态")
    void callbackFilterIgnoresOAuth2PreAuthentication() {
        var oauth2Token = new org.springframework.security.oauth2.client.authentication
            .OAuth2AuthenticationToken(oauth2User(), java.util.List.of(), "blogsclub");
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(
            Mono.just(new org.springframework.security.core.context.SecurityContextImpl(oauth2Token)));
        var filter = authenticationFilter(securityContextRepository);

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(exchange.getAttributes())
            .doesNotContainKey(BlogsClubAuthenticationWebFilter.PRE_AUTHENTICATION_ATTRIBUTE);
    }

    @Test
    @DisplayName("回调过滤器对已登录访客只订阅委托一次（v1.4.0 二次订阅回归）")
    void callbackFilterSubscribesDelegateExactlyOnceForSignedInVisitor() {
        var signedIn = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken("user-existing", "n/a",
            java.util.List.of(() -> "ROLE_USER"));
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(
            Mono.just(new org.springframework.security.core.context.SecurityContextImpl(signedIn)));
        var runs = new AtomicInteger();
        var filter = authenticationFilter(securityContextRepository, countingDelegate(runs));

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        // v1.4.0 composed the delegate with flatMap(...)+switchIfEmpty(...): the delegate is a
        // Mono<Void> and therefore always completes empty, so the switchIfEmpty fired for every
        // signed-in visitor and subscribed the delegate a second time — by then the
        // authorization request had been removed from the session, so the second attempt failed
        // with authorization_request_not_found and its error page overwrote the successful
        // redirect. The blogger saw "登录失败" even though the bind had succeeded.
        assertThat(runs).hasValue(1);
        assertThat(exchange.getAttributes())
            .containsEntry(BlogsClubAuthenticationWebFilter.PRE_AUTHENTICATION_ATTRIBUTE, signedIn);
    }

    @Test
    @DisplayName("回调过滤器对匿名访客只订阅委托一次")
    void callbackFilterSubscribesDelegateExactlyOnceForAnonymousVisitor() {
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(Mono.empty());
        var runs = new AtomicInteger();
        var filter = authenticationFilter(securityContextRepository, countingDelegate(runs));

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("会话里残留的 OAuth2 令牌时也只订阅委托一次")
    void callbackFilterSubscribesDelegateExactlyOnceForResidualOAuth2Token() {
        var oauth2Token = new org.springframework.security.oauth2.client.authentication
            .OAuth2AuthenticationToken(oauth2User(), java.util.List.of(), "blogsclub");
        var securityContextRepository = mock(ServerSecurityContextRepository.class);
        when(securityContextRepository.load(any())).thenReturn(
            Mono.just(new org.springframework.security.core.context.SecurityContextImpl(oauth2Token)));
        var runs = new AtomicInteger();
        var filter = authenticationFilter(securityContextRepository, countingDelegate(runs));

        var exchange = exchange("/login/oauth2/code/blogsclub?code=c&state=s");
        StepVerifier.create(filter.filter(exchange, chain(exchange))).verifyComplete();

        assertThat(runs).hasValue(1);
    }

    /**
     * Stand-in for the internal {@code OAuth2LoginAuthenticationWebFilter} delegate that counts
     * its subscriptions and completes empty — exactly like the real one, whose {@code Mono<Void>}
     * completion is what a {@code switchIfEmpty} downstream of it would misread as "nothing
     * happened" and re-subscribe (v1.4.0 production incident).
     */
    private static WebFilter countingDelegate(AtomicInteger runs) {
        return (exchange, chain) -> {
            runs.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    @DisplayName("Spring 构造器选择钉死公开构造器（v1.4.1 启动崩溃回归：No default constructor found）")
    void springConstructorSelectionPinsThePublicConstructor() {
        // Halo starts plugins through a plain Spring context, so bean creation runs
        // AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors. With several
        // constructors and no @Autowired it looks for a no-arg default constructor — there is
        // none, and plugin startup dies with "No default constructor found" /
        // NoSuchMethodException <init>() (exactly what v1.4.1 shipped: the plugin could not
        // start at all). The @Autowired on the public constructor must keep Spring on the
        // four-arg constructor, and the package-private test constructor must stay invisible.
        var postProcessor = new AutowiredAnnotationBeanPostProcessor() {
            Constructor<?>[] candidatesFor(Class<?> beanClass) {
                return determineCandidateConstructors(beanClass, beanClass.getName());
            }
        };
        var candidates = postProcessor.candidatesFor(BlogsClubAuthenticationWebFilter.class);

        assertThat(candidates).isNotNull();
        assertThat(candidates).hasSize(1);
        assertThat(candidates[0].getParameterCount()).isEqualTo(4);
        assertThat(java.lang.reflect.Modifier.isPublic(candidates[0].getModifiers())).isTrue();
    }

    private static org.springframework.security.oauth2.core.user.OAuth2User oauth2User() {
        return new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
            java.util.List.of(() -> "ROLE_USER"), java.util.Map.of("openid", "123"), "openid");
    }

    @Test
    @DisplayName("两个过滤器都排在无顺序注解的插件过滤器之前（复现 Halo 的排序）")
    void filtersSortAheadOfUnorderedPluginFilters() {
        // Exactly how Halo's SecurityWebFilterChainProxy orders the extensions of one
        // extension point: AnnotationAwareOrderComparator over the collected instances.
        // A filter without @Order sorts at Ordered.LOWEST_PRECEDENCE — where the official
        // plugin-oauth2's filters live — so ours must come first for both extension points,
        // no matter in which order the plugins were loaded or reloaded.
        var redirect = redirectFilter();
        var redirectGroup = new ArrayList<WebFilter>(
            List.of(unorderedRedirectFilter(), redirect));
        redirectGroup.sort(AnnotationAwareOrderComparator.INSTANCE);
        assertThat(redirectGroup.get(0)).isSameAs(redirect);

        var authentication = authenticationFilter();
        var authenticationGroup = new ArrayList<WebFilter>(
            List.of(unorderedAuthenticationFilter(), authentication));
        authenticationGroup.sort(AnnotationAwareOrderComparator.INSTANCE);
        assertThat(authenticationGroup.get(0)).isSameAs(authentication);
    }

    private static BlogsClubRedirectWebFilter redirectFilter() {
        var clientRegistrationRepository = mock(ReactiveClientRegistrationRepository.class);
        var configuration = mock(BlogsClubLoginConfiguration.class);
        when(configuration.getClientRegistrationRepository())
            .thenReturn(clientRegistrationRepository);
        when(configuration.getRequestCache())
            .thenReturn(new WebSessionServerRequestCache());
        return new BlogsClubRedirectWebFilter(configuration);
    }

    private static MockServerWebExchange exchange(String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri).build());
    }

    /**
     * Stand-in for the official plugin-oauth2's {@code HaloOAuth2RedirectWebFilter}: same
     * extension point, no {@code @Order} annotation.
     */
    private static WebFilter unorderedRedirectFilter() {
        return new HttpBasicSecurityWebFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
                return chain.filter(exchange);
            }
        };
    }

    /**
     * Stand-in for the official plugin-oauth2's {@code HaloOAuth2AuthenticationWebFilter}:
     * same extension point, no {@code @Order} annotation.
     */
    private static WebFilter unorderedAuthenticationFilter() {
        return new AuthenticationSecurityWebFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
                return chain.filter(exchange);
            }
        };
    }

    private static BlogsClubAuthenticationWebFilter authenticationFilter() {
        return authenticationFilter(mock(ServerSecurityContextRepository.class));
    }

    private static BlogsClubAuthenticationWebFilter authenticationFilter(
        ServerSecurityContextRepository securityContextRepository) {
        return authenticationFilter(securityContextRepository, null);
    }

    private static BlogsClubAuthenticationWebFilter authenticationFilter(
        ServerSecurityContextRepository securityContextRepository,
        @Nullable WebFilter delegateOverride) {
        var configuration = new BlogsClubLoginConfiguration(
            mock(ReactiveExtensionClient.class),
            mock(LoginHandlerEnhancer.class),
            mock(ExternalUrlSupplier.class));
        return new BlogsClubAuthenticationWebFilter(configuration,
            securityContextRepository,
            new OAuth2Properties(),
            mock(BlogsClubAccountProvisioningHandler.class),
            delegateOverride);
    }

    private static WebFilterChain chain(MockServerWebExchange exchange) {
        var chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        return chain;
    }
}
