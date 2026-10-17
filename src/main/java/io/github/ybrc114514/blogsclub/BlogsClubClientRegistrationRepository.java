package io.github.ybrc114514.blogsclub;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.util.Assert;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.AuthProvider;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;
import run.halo.app.infra.utils.JsonUtils;

/**
 * A reactive repository for the BlogsClub {@link ClientRegistration} that stores registrations
 * in the {@link ReactiveExtensionClient}. The static BlogsClub endpoints come from the
 * {@link BlogsClubClientRegistration} scheme, the {@code clientId} (AppID) comes from the
 * {@link AuthProvider}'s {@code configMapRef}, and the {@code clientSecret} (AppKey) is kept in a
 * Halo {@link Secret} — never in a ConfigMap.
 *
 * <p><b>Where the AppKey lives.</b> The halo-plugin-dev skill requires passwords, tokens and API
 * keys to be stored in a Halo {@code Secret} rather than a ConfigMap. The console's auth-provider
 * settings form, however, writes its values straight into the ConfigMap referenced by
 * {@code configMapRef}. To satisfy both, the AppKey is migrated on read: whenever the ConfigMap
 * still carries a non-blank {@code clientSecret}, it is written into the Secret
 * {@value #CLIENT_SECRET_NAME} (key {@value #CLIENT_SECRET_KEY}) and then removed from the
 * ConfigMap, so the credential comes to rest in the Secret even if the operator re-saves the form.
 * Migration failures are non-fatal — the request falls back to the form value.</p>
 *
 * @since 1.0.0
 */
@Slf4j
public class BlogsClubClientRegistrationRepository implements ReactiveClientRegistrationRepository {

    /** OAuth2 registration id of this plugin, matching the {@code auth-provider} resource name. */
    public static final String REGISTRATION_ID = "blogsclub";

    /** Halo Secret holding the BlogsClub AppKey. */
    public static final String CLIENT_SECRET_NAME = "blogsclub-oauth2-client-secret";

    /** Key of the AppKey inside {@link #CLIENT_SECRET_NAME}. */
    public static final String CLIENT_SECRET_KEY = "client-secret";

    static final String DEFAULT_REDIRECT_URL = "{baseUrl}/login/oauth2/code/{registrationId}";

    private final ReactiveExtensionClient client;

    private final ExternalUrlSupplier externalUrlSupplier;

    public BlogsClubClientRegistrationRepository(ReactiveExtensionClient client,
        ExternalUrlSupplier externalUrlSupplier) {
        this.client = client;
        this.externalUrlSupplier = externalUrlSupplier;
    }

    @Override
    public Mono<ClientRegistration> findByRegistrationId(String registrationId) {
        return client.fetch(AuthProvider.class, registrationId)
            .switchIfEmpty(Mono.error(new ProviderNotFoundException(
                "Unsupported OAuth2 provider: " + registrationId)))
            .flatMap(provider -> fetchEnabledProviders()
                .doOnNext(enabledNames -> {
                    if (!enabledNames.contains(registrationId)) {
                        throw new OAuth2AuthenticationException(
                            "Authentication provider is not enabled: " + registrationId);
                    }
                })
                .thenReturn(provider))
            .flatMap(this::getClientRegistrationMono);
    }

    private Mono<ClientRegistration> getClientRegistrationMono(AuthProvider authProvider) {
        Assert.notNull(authProvider, "The authProvider must not be null");
        final AuthProvider.ConfigMapRef configMapKeyRef = authProvider.getSpec().getConfigMapRef();
        final String group = authProvider.getSpec().getSettingRef().getGroup();
        return client.fetch(ConfigMap.class, configMapKeyRef.getName())
            .switchIfEmpty(Mono.error(new BlogsClubConfigException(
                BlogsClubConfigException.MISSING_CREDENTIALS)))
            .flatMap(configMap -> {
                var data = configMap.getData();
                String value = data == null ? "{}" : data.getOrDefault(group, "{}");
                if (StringUtils.isBlank(value)) {
                    value = "{}";
                }
                ClientConf clientConf;
                try {
                    clientConf = JsonUtils.jsonToObject(value, ClientConf.class);
                } catch (Exception e) {
                    throw new BlogsClubConfigException(
                        BlogsClubConfigException.MISSING_CREDENTIALS, e);
                }
                var conf = clientConf == null ? new ClientConf(null, null) : clientConf;
                return resolveClientSecret(conf, configMap, group)
                    .switchIfEmpty(Mono.error(new BlogsClubConfigException(
                        BlogsClubConfigException.MISSING_CREDENTIALS)))
                    .flatMap(clientSecret -> clientRegistrationMono(
                        conf.clientId(), clientSecret, authProvider));
            });
    }

    /**
     * Resolves the AppKey from the Halo Secret, migrating it out of the ConfigMap on the way.
     *
     * <p>An empty result means "no AppKey anywhere" and lets the caller raise the readable
     * missing-credentials error.</p>
     */
    private Mono<String> resolveClientSecret(ClientConf conf, ConfigMap configMap, String group) {
        if (StringUtils.isBlank(conf.clientSecret())) {
            // Nothing left in the form: the AppKey must already live in the Halo Secret.
            return readClientSecret();
        }
        // The console still keeps the AppKey in the ConfigMap. Move it into the Halo Secret (the
        // home the skill mandates for credentials) and strip it from the ConfigMap so it does not
        // rest there. Failures are non-fatal: this request still uses the form value, and the next
        // one retries the migration because the ConfigMap entry is left untouched.
        return upsertClientSecret(conf.clientSecret())
            .flatMap(written -> stripClientSecretFromConfigMap(configMap, group)
                .thenReturn(written))
            .map(BlogsClubClientRegistrationRepository::readClientSecretValue)
            .onErrorResume(error -> {
                log.warn("[BlogsClub] 将 AppKey 迁移到 Halo 密钥失败，本次仍使用表单值", error);
                return Mono.empty();
            })
            .switchIfEmpty(Mono.just(conf.clientSecret()));
    }

    private Mono<Secret> upsertClientSecret(String clientSecret) {
        return client.fetch(Secret.class, CLIENT_SECRET_NAME)
            .flatMap(existing -> {
                existing.setData(Map.of(CLIENT_SECRET_KEY,
                    clientSecret.getBytes(StandardCharsets.UTF_8)));
                return client.update(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var secret = new Secret();
                secret.setMetadata(new Metadata());
                secret.getMetadata().setName(CLIENT_SECRET_NAME);
                secret.setType(Secret.SECRET_TYPE_OPAQUE);
                secret.setData(Map.of(CLIENT_SECRET_KEY,
                    clientSecret.getBytes(StandardCharsets.UTF_8)));
                return client.create(secret);
            }));
    }

    /**
     * Removes the {@code clientSecret} entry from the auth-provider ConfigMap so the AppKey no
     * longer rests there. The {@code clientId} and every other key are preserved.
     */
    Mono<ConfigMap> stripClientSecretFromConfigMap(ConfigMap configMap, String group) {
        return Mono.fromCallable(() -> {
            var data = configMap.getData();
            if (data == null) {
                return null;
            }
            String value = data.get(group);
            if (StringUtils.isBlank(value)) {
                return null;
            }
            try {
                var node = JsonUtils.jsonToObject(value, com.fasterxml.jackson.databind.JsonNode.class);
                if (!node.isObject() || !node.has("clientSecret")) {
                    return null;
                }
                ((ObjectNode) node).remove("clientSecret");
                return JsonUtils.objectToJson(node);
            } catch (Exception e) {
                log.warn("[BlogsClub] 解析 AppID/AppKey 配置失败，保留原值", e);
                return null;
            }
        }).flatMap(stripped -> {
            if (stripped == null) {
                return Mono.empty();
            }
            configMap.getData().put(group, stripped);
            return client.update(configMap);
        });
    }

    private Mono<String> readClientSecret() {
        return client.fetch(Secret.class, CLIENT_SECRET_NAME)
            .map(BlogsClubClientRegistrationRepository::readClientSecretValue)
            .filter(clientSecret -> StringUtils.isNotBlank(clientSecret));
    }

    private static String readClientSecretValue(Secret secret) {
        var data = secret.getData();
        if (data == null) {
            return null;
        }
        var value = data.get(CLIENT_SECRET_KEY);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /**
     * Deliberately tolerant: an empty configuration used to blow up inside Jackson (which surfaced
     * as a cryptic {@code JsonParseException} and a bare HTTP 500), so the "is it configured?"
     * check lives in {@link #clientRegistrationMono} where a readable
     * message can be attached.
     */
    record ClientConf(String clientId, String clientSecret) {
    }

    private Mono<ClientRegistration> clientRegistrationMono(String clientId, String clientSecret,
        AuthProvider authProvider) {
        if (StringUtils.isBlank(clientId) || StringUtils.isBlank(clientSecret)) {
            return Mono.error(new BlogsClubConfigException(
                BlogsClubConfigException.MISSING_CREDENTIALS));
        }
        String registrationId = authProvider.getMetadata().getName();
        return client.fetch(BlogsClubClientRegistration.class, registrationId)
            .switchIfEmpty(Mono.error(new NotFoundException(
                "Oauth2 client registration " + registrationId + " not found")))
            .map(registration -> clientRegistrationBuilder(registration)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build());
    }

    ClientAuthenticationMethod toClientAuthenticationMethod(String method) {
        if (StringUtils.isBlank(method)) {
            return ClientAuthenticationMethod.NONE;
        }
        return new ClientAuthenticationMethod(method.toLowerCase());
    }

    AuthorizationGrantType toAuthorizationGrantType(String grantType) {
        if (StringUtils.isBlank(grantType)) {
            return AuthorizationGrantType.AUTHORIZATION_CODE;
        }
        return new AuthorizationGrantType(grantType.toLowerCase());
    }

    AuthenticationMethod toAuthenticationMethod(String method) {
        if (StringUtils.isBlank(method)) {
            return AuthenticationMethod.HEADER;
        }
        return new AuthenticationMethod(method.toLowerCase());
    }

    ClientRegistration.Builder clientRegistrationBuilder(BlogsClubClientRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException(
                "The clientRegistration in AuthProvider must not be null");
        }
        BlogsClubClientRegistration.BlogsClubClientRegistrationSpec spec = registration.getSpec();
        var redirectUri = defaultIfNull(spec.getRedirectUri(), DEFAULT_REDIRECT_URL);
        var externalUrl = externalUrlSupplier.getRaw();
        if (externalUrl != null) {
            // rewrite redirect URI if external Url is configured.
            redirectUri = UriComponentsBuilder.fromUriString(redirectUri)
                .uriVariables(Map.of("baseUrl", StringUtils.removeEnd(externalUrl.toString(), "/")))
                .build()
                .toString();
        }
        return ClientRegistration.withRegistrationId(registration.getMetadata().getName())
            .clientName(spec.getClientName())
            .clientAuthenticationMethod(
                toClientAuthenticationMethod(spec.getClientAuthenticationMethod()))
            .authorizationGrantType(
                toAuthorizationGrantType(spec.getAuthorizationGrantType()))
            .authorizationUri(spec.getAuthorizationUri())
            .issuerUri(spec.getIssuerUri())
            .jwkSetUri(spec.getJwkSetUri())
            .redirectUri(redirectUri)
            .scope(spec.getScopes())
            .tokenUri(spec.getTokenUri())
            .userInfoAuthenticationMethod(
                toAuthenticationMethod(spec.getUserInfoAuthenticationMethod()))
            .userInfoUri(spec.getUserInfoUri())
            .providerConfigurationMetadata(
                defaultIfNull(spec.getConfigurationMetadata(), Map.of()))
            .userNameAttributeName(spec.getUserNameAttributeName());
    }

    Mono<Set<String>> fetchEnabledProviders() {
        return client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
            .map(configMap -> {
                var authProvider = getAuthProvider(configMap);
                return authProvider.getStates().stream()
                    .filter(SystemSetting.AuthProviderState::isEnabled)
                    .map(SystemSetting.AuthProviderState::getName)
                    .collect(Collectors.toSet());
            })
            .defaultIfEmpty(Set.of());
    }

    @NonNull
    private static SystemSetting.AuthProvider getAuthProvider(ConfigMap configMap) {
        if (configMap.getData() == null) {
            configMap.setData(new HashMap<>());
        }

        Map<String, String> data = configMap.getData();
        String providerGroup = data.get(SystemSetting.AuthProvider.GROUP);
        SystemSetting.AuthProvider authProvider;
        if (StringUtils.isBlank(providerGroup)) {
            authProvider = new SystemSetting.AuthProvider();
        } else {
            authProvider = JsonUtils.jsonToObject(providerGroup, SystemSetting.AuthProvider.class);
        }

        if (authProvider.getStates() == null) {
            authProvider.setStates(List.of());
        }
        return authProvider;
    }
}
