package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.UserConnection;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;

/**
 * Covers the account provisioning that Halo's own OAuth2 chain does not perform: a BlogsClub
 * identity that is authenticated but not bound to any account yet.
 */
class BlogsClubAccountProvisioningHandlerTest {

    private static final String REGISTRATION_ID =
        BlogsClubClientRegistrationRepository.REGISTRATION_ID;

    private ReactiveExtensionClient client;

    private UserService userService;

    private BlogsClubAccountProvisioningHandler handler;

    @BeforeEach
    void setUp() {
        client = org.mockito.Mockito.mock(ReactiveExtensionClient.class);
        userService = org.mockito.Mockito.mock(UserService.class);
        handler = new BlogsClubAccountProvisioningHandler(client,
            new BlogsClubAccountProvisioner(client, userService,
                new BlogsClubUserPreCreatingHandler()));
    }

    private static OAuth2User principal(String openId, Map<String, Object> attributes) {
        var all = new java.util.HashMap<>(attributes);
        all.put("openid", openId);
        all.put("name", openId);
        return new DefaultOAuth2User(Set.of(), all, "openid");
    }

    private static Authentication blogsClubAuth(OAuth2User principal) {
        var registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
            .clientId("id")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/blogsclub")
            .authorizationUri("https://www.blogsclub.org/Oauth/Login")
            .tokenUri("https://www.blogsclub.org/Oauth/getOauthData?action=token")
            .userInfoUri("https://www.blogsclub.org/Oauth/getOauthData?action=userinfo")
            .userNameAttributeName("openid")
            .build();
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
            REGISTRATION_ID);
    }

    private void siteAllowsRegistration() {
        var configMap = new ConfigMap();
        configMap.setMetadata(new Metadata());
        configMap.getMetadata().setName(SystemSetting.SYSTEM_CONFIG);
        configMap.setData(Map.of(SystemSetting.User.GROUP, """
            {"allowRegistration":true,"mustVerifyEmailOnRegistration":true,
             "defaultRole":"authenticated"}"""));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(configMap));
    }

    private void siteForbidsRegistration() {
        var configMap = new ConfigMap();
        configMap.setMetadata(new Metadata());
        configMap.getMetadata().setName(SystemSetting.SYSTEM_CONFIG);
        configMap.setData(Map.of(SystemSetting.User.GROUP,
            "{\"allowRegistration\":false,\"defaultRole\":\"authenticated\"}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Mono.just(configMap));
    }

    private void expectCreatedAccountIsHandedBack() {
        when(client.fetch(eq(User.class), anyString())).thenReturn(Mono.empty());
        when(userService.createUser(any(User.class), any()))
            .thenAnswer(invocation -> {
                var user = (User) invocation.getArgument(0);
                user.getSpec().setDisplayName("某某某的自留地");
                return Mono.just(user);
            });
        when(client.create(any(UserConnection.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("首次登录的未绑定身份会自动建号并写入绑定关系")
    void provisionsAccountForUnboundIdentity() {
        when(client.listAll(any(Class.class), any(), any()))
            .thenReturn(Flux.empty());
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(
                blogsClubAuth(principal("123", Map.of("blog_name", "某某某的自留地"))), null))
            .assertNext(user -> {
                assertThat(user.getMetadata().getName()).startsWith("user-");
                assertThat(user.getSpec().getRegisteredAt()).isNotNull();
            })
            .verifyComplete();

        verify(client).create(any(UserConnection.class));
        verify(userService).createUser(any(User.class),
            org.mockito.ArgumentMatchers.eq(Set.of("authenticated")));
    }

    @Test
    @DisplayName("已绑定的身份不会重复建号")
    void doesNotProvisionAlreadyBoundIdentity() {
        var connection = new UserConnection();
        var spec = new UserConnection.UserConnectionSpec();
        spec.setRegistrationId(REGISTRATION_ID);
        spec.setProviderUserId("123");
        spec.setUsername("user-existing");
        connection.setSpec(spec);
        when(client.listAll(any(Class.class), any(), any()))
            .thenReturn(Flux.just(connection));

        StepVerifier.create(handler.provisionIfUnbound(blogsClubAuth(principal("123", Map.of())), null))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("站点关闭注册时不建号，交回 Halo 的绑定流程")
    void doesNotProvisionWhenRegistrationDisabled() {
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        siteForbidsRegistration();

        StepVerifier.create(handler.provisionIfUnbound(blogsClubAuth(principal("123", Map.of())), null))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("站点未配置默认角色时不建号")
    void doesNotProvisionWithoutDefaultRole() {
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        var configMap = new ConfigMap();
        configMap.setMetadata(new Metadata());
        configMap.getMetadata().setName(SystemSetting.SYSTEM_CONFIG);
        configMap.setData(Map.of(SystemSetting.User.GROUP, "{\"allowRegistration\":true}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
            .thenReturn(Mono.just(configMap));

        StepVerifier.create(handler.provisionIfUnbound(blogsClubAuth(principal("123", Map.of())), null))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("站点设置读取失败时按不开放注册处理")
    void failsClosedWhenSiteSettingsUnreadable() {
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
            .thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(handler.provisionIfUnbound(blogsClubAuth(principal("123", Map.of())), null))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("非 BlogsClub 认证一律不处理")
    void ignoresOtherProviders() {
        var registration = ClientRegistration.withRegistrationId("github")
            .clientId("id").clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/github")
            .authorizationUri("https://github.com/login/oauth/authorize")
            .tokenUri("https://github.com/login/oauth/access_token")
            .userNameAttributeName("id")
            .build();
        var auth = new OAuth2AuthenticationToken(principal("123", Map.of()),
            java.util.Set.of(), "github");

        StepVerifier.create(handler.provisionIfUnbound(auth, null)).verifyComplete();

        verify(client, never()).listAll(any(Class.class), any(), any());
        verify(userService, never()).createUser(any(User.class), any());
        assertThat(registration.getRegistrationId()).isEqualTo("github");
    }

    @Test
    @DisplayName("非 OAuth2 认证一律不处理")
    void ignoresNonOAuth2Authentication() {
        StepVerifier.create(handler.provisionIfUnbound(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "someone", "n/a"), null))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("其他认证方式的绑定关系不影响 BlogsClub 判断")
    void ignoresOtherProvidersConnections() {
        var connection = new UserConnection();
        var spec = new UserConnection.UserConnectionSpec();
        spec.setRegistrationId("github");
        spec.setProviderUserId("123");
        connection.setSpec(spec);
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.just(connection));
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(blogsClubAuth(principal("123", Map.of())), null))
            .assertNext(user -> assertThat(user.getMetadata().getName()).startsWith("user-"))
            .verifyComplete();
    }

    @Test
    @DisplayName("已登录访客绑定 BlogsClub 时不建新号（交回 Halo 的绑定流程）")
    void doesNotProvisionForSignedInVisitor() {
        var signedIn = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken("user-existing", "n/a",
            java.util.List.of(() -> "ROLE_USER"));
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(
                blogsClubAuth(principal("123", Map.of())), signedIn))
            .verifyComplete();

        // Nothing may be created — not even a look-ahead account: Halo core binds the
        // identity to the signed-in account (MapOAuth2AuthenticationFilter's
        // PRE_AUTHENTICATION branch). Production incident 2026-10-17: every bind attempt
        // from the user centre produced another user-xxxxxx account instead.
        verify(userService, never()).createUser(any(User.class), any());
        verify(client, never()).create(any(UserConnection.class));
        verify(client, never()).listAll(any(Class.class), any(), any());
    }

    @Test
    @DisplayName("已登录但身份已绑定时同样不建号")
    void doesNotProvisionForSignedInVisitorWithBoundIdentity() {
        var signedIn = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken("user-existing", "n/a",
            java.util.List.of(() -> "ROLE_USER"));
        var connection = new UserConnection();
        var spec = new UserConnection.UserConnectionSpec();
        spec.setRegistrationId(REGISTRATION_ID);
        spec.setProviderUserId("123");
        spec.setUsername("user-existing");
        connection.setSpec(spec);
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.just(connection));
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(
                blogsClubAuth(principal("123", Map.of())), signedIn))
            .verifyComplete();

        verify(userService, never()).createUser(any(User.class), any());
        verify(client, never()).create(any(UserConnection.class));
    }

    @Test
    @DisplayName("匿名令牌不算已登录（信任解析器语义与内核一致），仍会建号")
    void provisionsForAnonymousPreAuthentication() {
        var anonymous = new org.springframework.security.authentication
            .AnonymousAuthenticationToken("key", "anonymousUser",
            java.util.List.of(() -> "ROLE_ANONYMOUS"));
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(
                blogsClubAuth(principal("123", Map.of())), anonymous))
            .assertNext(user -> assertThat(user.getMetadata().getName()).startsWith("user-"))
            .verifyComplete();

        verify(userService).createUser(any(User.class), any());
    }

    @Test
    @DisplayName("未认证的预登录认证不算已登录，仍会建号")
    void provisionsForUnauthenticatedPreAuthentication() {
        var notSignedIn = new org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken("user-existing", "n/a");
        when(client.listAll(any(Class.class), any(), any())).thenReturn(Flux.empty());
        siteAllowsRegistration();
        expectCreatedAccountIsHandedBack();

        StepVerifier.create(handler.provisionIfUnbound(
                blogsClubAuth(principal("123", Map.of())), notSignedIn))
            .assertNext(user -> assertThat(user.getMetadata().getName()).startsWith("user-"))
            .verifyComplete();

        verify(userService).createUser(any(User.class), any());
    }
}
