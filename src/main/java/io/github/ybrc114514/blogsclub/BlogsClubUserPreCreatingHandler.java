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
 * the {@link User} but neither the OAuth2 principal nor the WebSession, and the generated username
 * is random ({@code user-ylitqejf}), so nothing on the incoming {@code User} identifies which
 * BlogsClub identity is being registered. Guessing — by matching the display name, the e-mail, or
 * "the only profile still waiting" — risks writing one blogger's e-mail onto another blogger's
 * account whenever two registrations overlap.</p>
 *
 * <p>Instead, {@link #remember} parks the profile under the verified BlogsClub identity (its
 * {@code openid}) <em>together with</em> the id of the session that authenticated it, and
 * {@link #preCreating} hands the profile back only when the session creating the account is the very
 * same one that authenticated. A profile can therefore never leak across users or sessions, and when
 * the association cannot be proven the account is simply created without backfill rather than with
 * somebody else's data.</p>
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
     * Verified profile, keyed by the BlogsClub {@code openid} that authenticated it.
     *
     * @param attributes BlogsClub userinfo attributes
     * @param sessionId  id of the web session that completed the OAuth2 exchange
     * @param parkedAt   creation timestamp in milliseconds
     */
    private record ParkedProfile(Map<String, Object> attributes, String sessionId, long parkedAt) {

        boolean isExpired() {
            return System.currentTimeMillis() - parkedAt > MAX_AGE.toMillis();
        }
    }

    private final Map<String, ParkedProfile> pendingProfiles = new ConcurrentHashMap<>();

    /**
     * Parks the BlogsClub attributes of an identity that has just authenticated, so the account Halo
     * is about to create can pick them up.
     *
     * <p>The profile is bound to {@code sessionId}: only an account created by the same session that
     * authenticated at BlogsClub may claim it.</p>
     *
     * @param registrationId OAuth2 registration id; only BlogsClub is relevant
     * @param principal      the authenticated BlogsClub principal
     * @param sessionId      id of the web session the OAuth2 exchange happened in
     */
    public void remember(String registrationId, OAuth2User principal, String sessionId) {
        if (!BlogsClubClientRegistrationRepository.REGISTRATION_ID.equals(registrationId)
            || principal == null
            || !StringUtils.hasText(sessionId)) {
            return;
        }
        String openId = principal.getName();
        if (!StringUtils.hasText(openId)) {
            return;
        }
        evictExpired();
        if (!pendingProfiles.containsKey(openId) && pendingProfiles.size() >= MAX_CACHE_SIZE) {
            // Drop the oldest entry so a burst of concurrent logins still gets handled.
            pendingProfiles.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().parkedAt()))
                .map(Map.Entry::getKey)
                .ifPresent(pendingProfiles::remove);
        }
        pendingProfiles.put(openId, new ParkedProfile(
            Map.copyOf(principal.getAttributes()), sessionId, System.currentTimeMillis()));
        log.info("[BlogsClub] 已缓存 BlogsClub 资料，等待建号时回填：openid={}", openId);
    }

    @Override
    public Mono<Void> preCreating(User user) {
        Map<String, Object> attributes = claim();
        if (attributes == null) {
            return Mono.empty();
        }
        applyEmail(user, attributes);
        applyDisplayName(user, attributes);
        applyAvatar(user, attributes);
        return Mono.empty();
    }

    /**
     * Claims the parked profile that provably belongs to the session creating the account.
     *
     * <p>This is the only place a profile may be handed out. It demands an unambiguous association
     * with the current session: when zero or several profiles are waiting for it, the account is
     * created without backfill rather than with data that belongs to somebody else.</p>
     *
     * @return the BlogsClub attributes when they can be attributed to the current session
     */
    private Map<String, Object> claim() {
        evictExpired();
        if (pendingProfiles.isEmpty()) {
            return null;
        }
        String currentSessionId = BlogsClubSessionContext.currentSessionId();
        if (currentSessionId == null) {
            // No session to attribute the profile to; never guess.
            log.info("[BlogsClub] 建号时无法确定当前会话，跳过资料回填");
            return null;
        }
        String matchedKey = null;
        for (var entry : pendingProfiles.entrySet()) {
            if (!currentSessionId.equals(entry.getValue().sessionId())) {
                continue;
            }
            if (matchedKey != null) {
                // Ambiguous: two identities raced on one session. Skip rather than mis-attribute.
                log.warn("[BlogsClub] 建号时同一会话存在多份 BlogsClub 资料，跳过回填以避免错配");
                return null;
            }
            matchedKey = entry.getKey();
        }
        if (matchedKey == null) {
            log.info("[BlogsClub] 建号时未找到与当前会话关联的 BlogsClub 资料，跳过回填");
            return null;
        }
        var claimed = pendingProfiles.remove(matchedKey);
        log.info("[BlogsClub] 建号时已按会话关联到 BlogsClub 资料：openid={}", matchedKey);
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