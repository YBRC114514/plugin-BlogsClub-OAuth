package io.github.ybrc114514.blogsclub;

import static org.apache.commons.lang3.ObjectUtils.defaultIfNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemSetting;
import run.halo.app.infra.utils.JsonUtils;

/**
 * A reactive repository for the BlogsClub {@link ClientRegistration} that stores registrations
 * in the {@link ReactiveExtensionClient}. The static BlogsClub endpoints come from the
 * {@link BlogsClubClientRegistration} scheme, while both the {@code clientId} (AppID) and
 * the {@code clientSecret} (AppKey) are read from the {@link AuthProvider}'s {@code configMapRef},
 * which is populated by the plugin's backend Setting form (AppID / AppKey config panel).
 *
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class BlogsClubClientRegistrationRepository implements ReactiveClientRegistrationRepository {

    /** OAuth2 registration id of this plugin, matching the {@code auth-provider} resource name. */
    public static final String REGISTRATION_ID = "blogsclub";

    static final String DEFAULT_REDIRECT_URL = "{baseUrl}/login/oauth2/code/{registrationId}";

    private final ReactiveExtensionClient client;

    private final ExternalUrlSupplier externalUrlSupplier;

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
        final String name = authProvider.getMetadata().getName();
        return client.fetch(ConfigMap.class, configMapKeyRef.getName())
            .map(ConfigMap::getData)
            .switchIfEmpty(Mono.error(new BlogsClubConfigException(
                BlogsClubConfigException.MISSING_CREDENTIALS)))
            .flatMap(data -> {
                String value = data.getOrDefault(group, "{}");
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
                return clientRegistrationMono(clientConf, authProvider);
            });
    }

    private Mono<ClientRegistration> clientRegistrationMono(ClientConf clientConf,
        AuthProvider authProvider) {
        if (clientConf == null || StringUtils.isBlank(clientConf.clientId())) {
            return Mono.error(new BlogsClubConfigException(
                BlogsClubConfigException.MISSING_CREDENTIALS));
        }
        if (StringUtils.isBlank(clientConf.clientSecret())) {
            return Mono.error(new BlogsClubConfigException(
                BlogsClubConfigException.MISSING_CREDENTIALS));
        }
        String registrationId = authProvider.getMetadata().getName();
        return client.fetch(BlogsClubClientRegistration.class, registrationId)
            .switchIfEmpty(Mono.error(new NotFoundException(
                "Oauth2 client registration " + registrationId + " not found")))
            .map(registration -> clientRegistrationBuilder(registration)
                .clientId(clientConf.clientId())
                .clientSecret(clientConf.clientSecret())
                .build());
    }

    /**
     * Deliberately tolerant: an empty configuration used to blow up inside Jackson (which surfaced
     * as a cryptic {@code JsonParseException} and a bare HTTP 500), so the "is it configured?"
     * check lives in {@link #clientRegistrationMono(ClientConf, AuthProvider)} where a readable
     * message can be attached.
     */
    record ClientConf(String clientId, String clientSecret) {
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
