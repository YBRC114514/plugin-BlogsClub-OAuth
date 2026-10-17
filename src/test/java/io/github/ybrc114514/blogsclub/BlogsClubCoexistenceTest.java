package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
        var configuration = new BlogsClubLoginConfiguration(
            mock(ReactiveExtensionClient.class),
            mock(LoginHandlerEnhancer.class),
            mock(ExternalUrlSupplier.class));
        return new BlogsClubAuthenticationWebFilter(configuration,
            mock(ServerSecurityContextRepository.class),
            new OAuth2Properties(),
            mock(BlogsClubAccountProvisioningHandler.class));
    }

    private static WebFilterChain chain(MockServerWebExchange exchange) {
        var chain = mock(WebFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());
        return chain;
    }
}
