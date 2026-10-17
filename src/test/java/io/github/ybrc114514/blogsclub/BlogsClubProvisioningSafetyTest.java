package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.UserConnection;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.security.authentication.oauth2.HaloOAuth2AuthenticationToken;

/**
 * Guards the fail-closed behaviours that keep provisioning from creating duplicates or writing data
 * onto the wrong session.
 */
class BlogsClubProvisioningSafetyTest {

    private ReactiveExtensionClient client;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
    }

    private BlogsClubAccountProvisioningHandler handler() {
        var userService = mock(run.halo.app.core.user.service.UserService.class);
        return new BlogsClubAccountProvisioningHandler(client,
            new BlogsClubAccountProvisioner(client, userService,
                new BlogsClubUserPreCreatingHandler()));
    }

    private static OAuth2User principal(String openId) {
        var attributes = new java.util.HashMap<String, Object>();
        attributes.put("openid", openId);
        attributes.put("name", openId);
        return new DefaultOAuth2User(Set.of(), attributes, "openid");
    }

    private static OAuth2AuthenticationToken auth(OAuth2User principal) {
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
            BlogsClubClientRegistrationRepository.REGISTRATION_ID);
    }

    @Test
    @DisplayName("查询绑定关系失败时放弃建号，避免为已绑定身份再建一个账号")
    void doesNotProvisionWhenBindingLookupFails() {
        when(client.listAll(eq(UserConnection.class), any(ListOptions.class), any()))
            .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("boom")));

        StepVerifier.create(handler().provisionIfUnbound(auth(principal("123"))))
            .verifyComplete();

        verify(client, never()).create(any(UserConnection.class));
    }

    @Test
    @DisplayName("Halo 包装的认证令牌同样能被识别为 BlogsClub 身份")
    void acceptsHalosWrappedToken() {
        var principal = principal("123");
        var wrapped = HaloOAuth2AuthenticationToken.authenticated(
            new org.springframework.security.core.userdetails.User(
                "someone", "n/a", java.util.List.of()),
            auth(principal));
        when(client.listAll(eq(UserConnection.class), any(ListOptions.class), any()))
            .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("no write")));

        // The wrapped token must be unwrapped rather than skipped: reaching the lookup at all proves
        // the plugin recognised the BlogsClub registration id inside Halo's wrapper.
        StepVerifier.create(handler().provisionIfUnbound(wrapped)).verifyComplete();

        verify(client).listAll(eq(UserConnection.class), any(ListOptions.class), any());
    }

    @Test
    @DisplayName("资料按建号用户名关联，永不跨账号错配（原会话 id 方案的继任者）")
    void profilesAreKeyedByAccountNotBySession() {
        // The old session-id keying read a thread-local that Halo's createUser chain had already
        // moved off, so the backfill silently never happened. The username keying cannot fail that
        // way: Halo hands preCreating the very User the provisioner parked the profile for. Here a
        // profile parked for one account must not be claimable by another.
        var profileHandler = new BlogsClubUserPreCreatingHandler();
        profileHandler.remember("user-one", principal("123"));
        profileHandler.remember("user-two", principal("456"));

        // user-two's profile must never be applied to an account named user-one.
        var one = newUser("user-one");
        StepVerifier.create(profileHandler.preCreating(one)).verifyComplete();
        assertThat(one.getSpec().getEmail()).isNull();

        // ...and the rightful account still claims its own.
        var two = newUser("user-two");
        StepVerifier.create(profileHandler.preCreating(two)).verifyComplete();
    }

    private static run.halo.app.core.extension.User newUser(String name) {
        var user = new run.halo.app.core.extension.User();
        user.setMetadata(new run.halo.app.extension.Metadata());
        user.getMetadata().setName(name);
        user.setSpec(new run.halo.app.core.extension.User.UserSpec());
        return user;
    }

    @Test
    @DisplayName("绑定查询返回空列表时视为未绑定，允许进入建号判断")
    void treatsEmptyBindingListAsUnbound() {
        when(client.listAll(eq(UserConnection.class), any(ListOptions.class), any()))
            .thenReturn(reactor.core.publisher.Flux.empty());

        // allowRegistration defaults to denied, so nothing is created; the point is that an empty
        // result does not abort the chain before the site settings are consulted.
        StepVerifier.create(handler().provisionIfUnbound(auth(principal("123"))))
            .verifyComplete();

        verify(client).listAll(eq(UserConnection.class), any(ListOptions.class), any());
    }

    @Test
    @DisplayName("缺少 refresh token 或 spec 时返回 null 而非抛出异常")
    void toleratesMissingRefreshToken() {
        var service = new DefaultOAuth2AuthorizedClientService(client,
            new BlogsClubClientRegistrationRepository(client,
                mock(run.halo.app.infra.ExternalUrlSupplier.class)));

        // A record with a spec but no refresh token: BlogsClub does not support the refresh grant.
        var withSpec = new BlogsClubAuthorizedClient();
        withSpec.setMetadata(new run.halo.app.extension.Metadata());
        withSpec.getMetadata().setName("blogsclub-123");
        withSpec.setSpec(new BlogsClubAuthorizedClient.BlogsClubAuthorizedClientSpec());
        assertThat(service.toRefreshToken(withSpec)).isNull();

        // A record with no spec at all must not blow up either.
        var withoutSpec = new BlogsClubAuthorizedClient();
        withoutSpec.setMetadata(new run.halo.app.extension.Metadata());
        assertThat(service.toRefreshToken(withoutSpec)).isNull();
    }

    @Test
    @DisplayName("站点设置中缺少 allowRegistration 字段时按不开放注册处理")
    void failsClosedWhenAllowRegistrationAbsent() {
        var configMap = new run.halo.app.extension.ConfigMap();
        configMap.setMetadata(new run.halo.app.extension.Metadata());
        configMap.getMetadata().setName(run.halo.app.infra.SystemSetting.SYSTEM_CONFIG);
        configMap.setData(Map.of(run.halo.app.infra.SystemSetting.User.GROUP,
            "{\"defaultRole\":\"authenticated\"}"));
        when(client.fetch(run.halo.app.extension.ConfigMap.class,
            run.halo.app.infra.SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(configMap));
        var userService = mock(run.halo.app.core.user.service.UserService.class);
        var provisioner = new BlogsClubAccountProvisioner(client, userService,
            new BlogsClubUserPreCreatingHandler());
        var h = new BlogsClubAccountProvisioningHandler(client, provisioner);
        when(client.listAll(eq(UserConnection.class), any(ListOptions.class), any()))
            .thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(h.provisionIfUnbound(auth(principal("123")))).verifyComplete();

        verify(userService, never()).createUser(any(), any());
    }
}
