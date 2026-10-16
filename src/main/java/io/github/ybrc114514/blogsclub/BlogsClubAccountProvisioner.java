package io.github.ybrc114514.blogsclub;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.UserConnection;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.SystemSetting;

/**
 * Creates the Halo account for a blogger whose BlogsClub identity is authenticated but not bound to
 * any account yet.
 *
 * <p>Halo's own OAuth2 handling only ever writes a {@link UserConnection}. On an unbound identity it
 * logs the request out and redirects to {@code /login?oauth2_bind}, which drops the blogger into a
 * manual binding flow instead of the promised "sign in and an account is created for you". This
 * service supplies the missing half: it provisions the {@link User} and then binds the
 * {@link UserConnection} to it.</p>
 *
 * <p><b>The site owner's registration policy is respected.</b> {@code allowRegistration} gates the
 * whole flow, and {@code defaultRole} is read from the site settings rather than hard-coded, so a
 * site that closed registration gets no new accounts from BlogsClub either. When the policy forbids
 * registration the caller falls back to Halo's binding flow instead of force-creating an account.</p>
 *
 * <p>Profile data is deliberately <em>not</em> written here. {@link BlogsClubUserPreCreatingHandler}
 * fills the e-mail, display name and avatar from inside {@link UserService#createUser}, bound to the
 * session that authenticated — see that class for why the association has to be provable.</p>
 *
 * @since 1.1.0
 */
@Slf4j
@Component
public class BlogsClubAccountProvisioner {

    /**
     * Mirrors Halo's own OAuth2 registration service: {@code user-} plus eight random lowercase
     * alphanumerics, retried while the name is taken. Halo validates user names against
     * {@code NAME_PATTERN}, which this prefix-and-random shape always satisfies.
     */
    private static final String USERNAME_PREFIX = "user-";

    private static final int USERNAME_RANDOM_LENGTH = 8;

    /** Halo gives up after this many attempts; we mirror it so behaviour stays comparable. */
    private static final int USERNAME_MAX_ATTEMPTS = 20;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveExtensionClient client;

    private final UserService userService;

    public BlogsClubAccountProvisioner(ReactiveExtensionClient client, UserService userService) {
        this.client = client;
        this.userService = userService;
    }

    /**
     * Creates the account and binds the BlogsClub identity to it.
     *
     * @param principal  the authenticated BlogsClub principal; its name attribute is the openid
     * @param openId     the verified BlogsClub openid
     * @return the newly created account
     */
    public Mono<User> provision(OAuth2User principal, String openId) {
        return fetchUserSetting()
            .filter(SiteUserSetting::allowRegistration)
            .switchIfEmpty(Mono.defer(() -> {
                log.info("[BlogsClub] 站点未开放注册，跳过自动建号，交由 Halo 的账号绑定流程处理："
                    + "openid={}", openId);
                return Mono.empty();
            }))
            .flatMap(setting -> {
                if (!StringUtils.hasText(setting.defaultRole())) {
                    log.warn("[BlogsClub] 站点未配置默认角色，无法自动建号：openid={}", openId);
                    return Mono.empty();
                }
                return createAccount(setting, openId);
            });
    }

    private Mono<User> createAccount(SiteUserSetting setting, String openId) {
        return resolveUsername(openId)
            .flatMap(username -> {
                var user = new User();
                user.setMetadata(new Metadata());
                user.getMetadata().setName(username);
                user.getSpec().setRegisteredAt(Instant.now());
                // No password is set: this is an OAuth2-only account. Halo treats a blank password
                // as "cannot sign in with a password", which is what we want — the blogger cannot
                // take over an OAuth2 account through the password reset flow.
                //
                // Note that BlogsClubUserPreCreatingHandler fills displayName / email / avatar
                // inside createUser, right before the extension is persisted.
                return userService.createUser(user, Set.of(setting.defaultRole()))
                    .flatMap(created -> bindIdentity(openId, created)
                        .thenReturn(created));
            })
            .doOnNext(created -> log.info(
                "[BlogsClub] 已为未绑定用户自动建号：username={}, openid={}",
                created.getMetadata().getName(), openId))
            .onErrorResume(error -> {
                log.error("[BlogsClub] 自动建号失败：openid={}", openId, error);
                return Mono.empty();
            });
    }

    /**
     * Binds the BlogsClub identity to the freshly created account so later sign-ins take the
     * "already bound" branch.
     *
     * <p>{@code UserConnectionService} lives in Halo's application module and is not exposed to
     * plugins, so the connection is written through the extension client directly. The metadata
     * uses {@code generateName} exactly like Halo's own connection service does, which lets the
     * client assign the {@code <username>-<random>} name.</p>
     */
    private Mono<Void> bindIdentity(String openId, User user) {
        return Mono.fromCallable(() -> {
            var connection = new UserConnection();
            var metadata = new Metadata();
            metadata.setGenerateName(user.getMetadata().getName() + "-");
            connection.setMetadata(metadata);
            var spec = new UserConnection.UserConnectionSpec();
            spec.setRegistrationId(BlogsClubClientRegistrationRepository.REGISTRATION_ID);
            spec.setUsername(user.getMetadata().getName());
            spec.setProviderUserId(openId);
            spec.setUpdatedAt(Instant.now());
            connection.setSpec(spec);
            return connection;
        })
            .flatMap(client::create)
            .doOnNext(created -> log.info("[BlogsClub] 已绑定 BlogsClub 身份：username={}, openid={}",
                user.getMetadata().getName(), openId))
            .then();
    }

    /**
     * Generates a username that is not taken yet. BlogsClub's openid is numeric and opaque, so it
     * is never used as the username directly — only logged.
     */
    private Mono<String> resolveUsername(String openId) {
        return Mono.defer(() -> {
            var candidate = USERNAME_PREFIX
                + RandomStringUtils.secure().nextAlphanumeric(USERNAME_RANDOM_LENGTH)
                    .toLowerCase(java.util.Locale.ROOT);
            return client.fetch(User.class, candidate)
                .hasElement()
                .filter(taken -> !taken)
                .map(free -> candidate);
        }).repeat(USERNAME_MAX_ATTEMPTS)
            .next()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                "Failed to generate a unique username for BlogsClub openid " + openId)));
    }

    /**
     * Reads the site's user registration settings.
     *
     * <p>Halo's {@code SystemConfigFetcher} lives in the application module and is not available to
     * plugins, so the {@code system} ConfigMap is read directly. Only the override map is consulted:
     * it already carries whatever the administrator saved, and an absent key means "not configured",
     * which we treat as "registration not allowed" — the fail-closed choice.</p>
     */
    private Mono<SiteUserSetting> fetchUserSetting() {
        return Mono.fromCallable(() -> client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)
                .onErrorResume(e -> Mono.empty()))
            .flatMap(mono -> mono)
            .map(BlogsClubAccountProvisioner::toUserSetting)
            .defaultIfEmpty(SiteUserSetting.denied());
    }

    private static SiteUserSetting toUserSetting(ConfigMap configMap) {
        Map<String, String> data = configMap.getData();
        if (data == null) {
            return SiteUserSetting.denied();
        }
        var json = data.get(SystemSetting.User.GROUP);
        if (!StringUtils.hasText(json)) {
            return SiteUserSetting.denied();
        }
        try {
            var node = OBJECT_MAPPER.readTree(json);
            var allowRegistration = node.path("allowRegistration").asBoolean(false);
            var defaultRole = node.path("defaultRole").asText("");
            return new SiteUserSetting(allowRegistration, defaultRole);
        } catch (Exception e) {
            log.warn("[BlogsClub] 解析站点用户设置失败，按不开放注册处理", e);
            return SiteUserSetting.denied();
        }
    }

    /**
     * The site's user registration policy, as far as BlogsClub provisioning is concerned.
     *
     * <p>Deliberately does <em>not</em> carry {@code mustVerifyEmailOnRegistration}: Halo reads that
     * flag only in {@code UserServiceImpl#signUp}, i.e. the self-service form. OAuth2 provisioning goes
     * through {@code createUser}, which validates neither the flag nor the e-mail, so honouring it
     * here would mean inventing a verification flow Halo does not provide here.</p>
     *
     * @param allowRegistration whether the site lets new accounts be created at all
     * @param defaultRole       role granted to new accounts; must be non-blank
     */
    public record SiteUserSetting(boolean allowRegistration, String defaultRole) {

        static SiteUserSetting denied() {
            return new SiteUserSetting(false, "");
        }
    }
}
