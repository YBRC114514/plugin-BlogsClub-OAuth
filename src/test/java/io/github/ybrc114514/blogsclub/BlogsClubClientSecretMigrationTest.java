package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.AuthProvider;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;
import run.halo.app.infra.utils.JsonUtils;

/**
 * Covers where the AppKey (client secret) is kept.
 *
 * <p>The halo-plugin-dev skill requires passwords, tokens and API keys to live in a Halo
 * {@link Secret} — never in a ConfigMap. The console's auth-provider form however writes its
 * values into the ConfigMap referenced by {@code configMapRef}. The repository therefore migrates
 * the AppKey into the Secret on read and strips it from the ConfigMap, so the credential comes to
 * rest in the Secret even when the operator re-saves the form.</p>
 */
class BlogsClubClientSecretMigrationTest {

    private ReactiveExtensionClient client;

    private BlogsClubClientRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        repository = new BlogsClubClientRegistrationRepository(client,
            mock(ExternalUrlSupplier.class));
    }

    private void authProviderEnabled() {
        var authProvider = new AuthProvider();
        authProvider.setMetadata(new Metadata());
        authProvider.getMetadata().setName("blogsclub");
        var spec = new AuthProvider.AuthProviderSpec();
        var settingRef = new AuthProvider.SettingRef();
        settingRef.setName("blogsclub-oauth2-setting");
        settingRef.setGroup("blogsclubOauth");
        spec.setSettingRef(settingRef);
        var configMapRef = new AuthProvider.ConfigMapRef();
        configMapRef.setName("oauth2-blogsclub-config");
        spec.setConfigMapRef(configMapRef);
        authProvider.setSpec(spec);
        when(client.fetch(AuthProvider.class, "blogsclub")).thenReturn(Mono.just(authProvider));

        var system = new ConfigMap();
        system.setMetadata(new Metadata());
        system.getMetadata().setName(SystemSetting.SYSTEM_CONFIG);
        system.setData(Map.of(SystemSetting.AuthProvider.GROUP,
            "{\"states\":[{\"name\":\"blogsclub\",\"enabled\":true}]}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
            .thenReturn(Mono.just(system));
    }

    private void clientRegistrationPresent() {
        var registration = new BlogsClubClientRegistration();
        registration.setMetadata(new Metadata());
        registration.getMetadata().setName("blogsclub");
        var spec = new BlogsClubClientRegistration.BlogsClubClientRegistrationSpec();
        spec.setClientName("BlogsClub");
        spec.setAuthorizationUri("https://www.blogsclub.org/Oauth/Login");
        spec.setTokenUri("https://www.blogsclub.org/Oauth/getOauthData?action=token");
        spec.setUserInfoUri("https://www.blogsclub.org/Oauth/getOauthData?action=userinfo");
        spec.setUserNameAttributeName("openid");
        registration.setSpec(spec);
        when(client.fetch(BlogsClubClientRegistration.class, "blogsclub"))
            .thenReturn(Mono.just(registration));
    }

    private ConfigMap authProviderConfig(String groupJson) {
        var configMap = new ConfigMap();
        configMap.setMetadata(new Metadata());
        configMap.getMetadata().setName("oauth2-blogsclub-config");
        var data = new HashMap<String, String>();
        data.put("blogsclubOauth", groupJson);
        configMap.setData(data);
        return configMap;
    }

    @Test
    @DisplayName("表单里的 AppKey 被迁移进 Halo 密钥，并从 ConfigMap 中移除")
    void migratesFormSecretIntoHaloSecret() {
        authProviderEnabled();
        clientRegistrationPresent();
        when(client.fetch(ConfigMap.class, "oauth2-blogsclub-config"))
            .thenReturn(Mono.just(authProviderConfig(
                "{\"clientId\":\"app-id\",\"clientSecret\":\"app-key\"}")));
        when(client.fetch(Secret.class,
            BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME))
            .thenReturn(Mono.empty());
        when(client.create(any(Secret.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(repository.findByRegistrationId("blogsclub"))
            .assertNext(registration -> {
                assertThat(registration.getClientId()).isEqualTo("app-id");
                assertThat(registration.getClientSecret()).isEqualTo("app-key");
            })
            .verifyComplete();

        // The AppKey is written into the Halo Secret...
        verify(client).create(any(Secret.class));
        // ...and stripped from the ConfigMap it used to rest in.
        verify(client).update(any(ConfigMap.class));
    }

    @Test
    @DisplayName("已迁移后从 Halo 密钥读取 AppKey，不再动 ConfigMap")
    void readsSecretFromHaloSecretAfterMigration() {
        authProviderEnabled();
        clientRegistrationPresent();
        when(client.fetch(ConfigMap.class, "oauth2-blogsclub-config"))
            .thenReturn(Mono.just(authProviderConfig("{\"clientId\":\"app-id\"}")));
        var secret = new Secret();
        secret.setMetadata(new Metadata());
        secret.getMetadata().setName(BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME);
        secret.setData(Map.of(BlogsClubClientRegistrationRepository.CLIENT_SECRET_KEY,
            "app-key".getBytes(StandardCharsets.UTF_8)));
        when(client.fetch(Secret.class,
            BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME))
            .thenReturn(Mono.just(secret));

        StepVerifier.create(repository.findByRegistrationId("blogsclub"))
            .assertNext(registration -> {
                assertThat(registration.getClientId()).isEqualTo("app-id");
                assertThat(registration.getClientSecret()).isEqualTo("app-key");
            })
            .verifyComplete();

        verify(client, never()).create(any(Secret.class));
        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    @DisplayName("AppID 与 AppKey 都不存在时抛出可读的配置缺失错误")
    void failsWithReadableErrorWhenCredentialsMissing() {
        authProviderEnabled();
        clientRegistrationPresent();
        when(client.fetch(ConfigMap.class, "oauth2-blogsclub-config"))
            .thenReturn(Mono.just(authProviderConfig("{}")));
        when(client.fetch(Secret.class,
            BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME))
            .thenReturn(Mono.empty());

        StepVerifier.create(repository.findByRegistrationId("blogsclub"))
            .verifyError(BlogsClubConfigException.class);
    }

    @Test
    @DisplayName("从 ConfigMap 剥离 AppKey 后 JSON 仍合法且保留 AppID")
    void strippedConfigMapJsonStaysValid() {
        var configMap = authProviderConfig("{\"clientId\":\"app-id\",\"clientSecret\":\"app-key\"}");
        when(client.update(any(ConfigMap.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(
                repository.stripClientSecretFromConfigMap(configMap, "blogsclubOauth"))
            .assertNext(stripped -> {
                var value = stripped.getData().get("blogsclubOauth");
                assertThat(value).contains("app-id");
                assertThat(value).doesNotContain("app-key");
                var conf = JsonUtils.jsonToObject(value,
                    BlogsClubClientRegistrationRepository.ClientConf.class);
                assertThat(conf.clientId()).isEqualTo("app-id");
                assertThat(conf.clientSecret()).isNull();
            })
            .verifyComplete();
    }

    @Test
    @DisplayName("没有 AppKey 的配置不会被无谓改写")
    void configMapWithoutSecretIsLeftAlone() {
        var configMap = authProviderConfig("{\"clientId\":\"app-id\"}");

        StepVerifier.create(
                repository.stripClientSecretFromConfigMap(configMap, "blogsclubOauth"))
            .verifyComplete();

        verify(client, never()).update(any(ConfigMap.class));
    }

    @Test
    @DisplayName("ClientRegistration 的基本装配不受密钥来源影响")
    void buildsRegistrationWithSecretFromEitherSource() {
        authProviderEnabled();
        clientRegistrationPresent();
        when(client.fetch(ConfigMap.class, "oauth2-blogsclub-config"))
            .thenReturn(Mono.just(authProviderConfig("{\"clientId\":\"app-id\"}")));
        var secret = new Secret();
        secret.setMetadata(new Metadata());
        secret.getMetadata().setName(BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME);
        secret.setData(Map.of(BlogsClubClientRegistrationRepository.CLIENT_SECRET_KEY,
            "app-key".getBytes(StandardCharsets.UTF_8)));
        when(client.fetch(Secret.class,
            BlogsClubClientRegistrationRepository.CLIENT_SECRET_NAME))
            .thenReturn(Mono.just(secret));

        StepVerifier.create(repository.findByRegistrationId("blogsclub"))
            .assertNext((ClientRegistration registration) -> {
                assertThat(registration.getRegistrationId()).isEqualTo("blogsclub");
                assertThat(registration.getClientName()).isEqualTo("BlogsClub");
                // The redirect URI keeps its template shape: {baseUrl} and {registrationId} are
                // expanded per request by Spring Security, not at build time.
                assertThat(registration.getRedirectUri())
                    .isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
            })
            .verifyComplete();
    }
}
