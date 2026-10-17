package io.github.ybrc114514.blogsclub;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserPreCreatingHandler;

/**
 * Writes the BlogsClub profile onto a Halo account that is about to be created, so a blogger
 * signing in for the first time does not have to type or verify an e-mail address.
 *
 * <p><b>How the profile is bound to the account.</b> Halo provisions the account inside its own
 * OAuth2 filter chain and only afterwards invokes {@link UserPreCreatingHandler}. That hook receives
 * the {@link User} but neither the OAuth2 principal nor any request context, and the generated
 * username is random ({@code user-ylitqejf}), so nothing on the incoming {@code User} identifies
 * which BlogsClub identity is being registered — unless the plugin makes that link itself.
 *
 * <p>{@link BlogsClubAccountProvisioner} generates the username and parks the profile under
 * <em>that very username</em> right before calling {@code UserService#createUser}. Halo then hands
 * the same {@code User} (carrying {@code metadata.name}) to {@link #preCreating}, which claims the
 * profile by name. The association is therefore carried inside the object Halo itself passes
 * through its reactive chain — no session id, no thread-local state, nothing that a reactive
 * thread hop could drop. A profile can only ever be claimed by the one account it was parked for,
 * so one blogger's e-mail can never land on another blogger's account, and when no profile was
 * parked the account is simply created without backfill.</p>
 *
 * <p>An earlier revision keyed the profile by the web session id read from a thread-local. That
 * value was set on the Netty event-loop thread but read back inside Halo's {@code createUser}
 * chain, which by then had already moved onto R2DBC worker threads — so the backfill silently
 * never happened. Keying by the username Halo is about to create removes that failure mode
 * entirely.</p>
 *
 * <p>Entries are removed as soon as they are claimed and expire on their own, so the map stays
 * small. Only blank fields are filled; anything already set is left untouched.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class BlogsClubUserPreCreatingHandler implements UserPreCreatingHandler {

    /** Upper bound on parked profiles; protects against unbounded growth under abuse. */
    private static final int MAX_CACHE_SIZE = 256;

    /** A parked profile older than this can no longer belong to the account being created. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    /**
     * Verified profile, keyed by the username of the account it may be written onto.
     *
     * @param attributes BlogsClub userinfo attributes
     * @param parkedAt   creation timestamp in milliseconds
     */
    private record ParkedProfile(Map<String, Object> attributes, long parkedAt) {

        boolean isExpired() {
            return System.currentTimeMillis() - parkedAt > MAX_AGE.toMillis();
        }
    }

    private final Map<String, ParkedProfile> pendingProfiles = new ConcurrentHashMap<>();

    /**
     * Parks the BlogsClub attributes of an identity that is about to get an account, keyed by the
     * username that account will be created under.
     *
     * @param username  the name of the account {@code createUser} is about to create
     * @param principal the authenticated BlogsClub principal
     */
    public void remember(String username, OAuth2User principal) {
        if (!StringUtils.hasText(username) || principal == null) {
            return;
        }
        String openId = principal.getName();
        if (!StringUtils.hasText(openId)) {
            return;
        }
        evictExpired();
        if (!pendingProfiles.containsKey(username) && pendingProfiles.size() >= MAX_CACHE_SIZE) {
            // Drop the oldest entry so a burst of concurrent logins still gets handled.
            pendingProfiles.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().parkedAt()))
                .map(Map.Entry::getKey)
                .ifPresent(pendingProfiles::remove);
        }
        pendingProfiles.put(username, new ParkedProfile(
            Map.copyOf(principal.getAttributes()), System.currentTimeMillis()));
        log.debug("[BlogsClub] 已缓存 BlogsClub 资料，等待建号时回填：username={}", username);
    }

    /**
     * Drops a parked profile that will never be claimed, e.g. because account creation failed.
     *
     * @param username the name the profile was parked under
     */
    public void forget(String username) {
        if (username != null) {
            pendingProfiles.remove(username);
        }
    }

    @Override
    public Mono<Void> preCreating(User user) {
        Map<String, Object> attributes = claim(user);
        if (attributes == null) {
            return Mono.empty();
        }
        applyEmail(user, attributes);
        applyDisplayName(user, attributes);
        applyAvatar(user, attributes);
        return Mono.empty();
    }

    /**
     * Claims the profile parked for the account Halo is about to create.
     *
     * <p>The only association needed is the username, which {@link BlogsClubAccountProvisioner}
     * generated and Halo carries on the very {@code User} handed to this hook. A profile that was
     * never parked — or one that belongs to a different account — is simply not returned, so the
     * account is created without backfill rather than with somebody else's data.</p>
     *
     * @return the BlogsClub attributes when a profile was parked for this exact account
     */
    private Map<String, Object> claim(User user) {
        evictExpired();
        if (pendingProfiles.isEmpty()) {
            return null;
        }
        var username = user.getMetadata().getName();
        var claimed = pendingProfiles.remove(username);
        if (claimed == null) {
            log.info("[BlogsClub] 建号时未找到与该账号关联的 BlogsClub 资料，跳过回填：username={}",
                username);
            return null;
        }
        log.debug("[BlogsClub] 建号时已按账号关联到 BlogsClub 资料：username={}", username);
        return claimed.attributes();
    }

    private void evictExpired() {
        pendingProfiles.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Writes the BlogsClub e-mail onto the account. Halo treats a non-blank address as "already
     * provided" and therefore does not push the blogger into the e-mail verification flow; the
     * address comes from the identity provider that has just authenticated them, so it is trusted.
     */
    private void applyEmail(User user, Map<String, Object> attributes) {
        if (StringUtils.hasText(user.getSpec().getEmail())) {
            return;
        }
        String email = text(attributes, "email");
        if (!isValidEmail(email)) {
            log.info("[BlogsClub] BlogsClub 未返回可用邮箱，账号邮箱保持为空：username={}",
                user.getMetadata().getName());
            return;
        }
        user.getSpec().setEmail(email);
        log.info("[BlogsClub] 已自动写入 BlogsClub 邮箱：username={}",
            user.getMetadata().getName());
    }

    private void applyDisplayName(User user, Map<String, Object> attributes) {
        if (StringUtils.hasText(user.getSpec().getDisplayName())) {
            return;
        }
        String blogName = text(attributes, "blog_name");
        if (StringUtils.hasText(blogName)) {
            user.getSpec().setDisplayName(blogName);
        }
    }

    private void applyAvatar(User user, Map<String, Object> attributes) {
        if (StringUtils.hasText(user.getSpec().getAvatar())) {
            return;
        }
        String avatar = text(attributes, "blogger_avatar");
        if (StringUtils.hasText(avatar) && avatar.startsWith("https://")) {
            user.getSpec().setAvatar(avatar);
        }
    }

    private static String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Shape check only: BlogsClub is trusted, but a malformed value must not break sign-up. */
    private static boolean isValidEmail(String email) {
        if (!StringUtils.hasText(email) || email.length() > 254 || email.contains(" ")) {
            return false;
        }
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }
}
