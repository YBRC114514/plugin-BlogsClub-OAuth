package run.halo.plugin.blogsclub;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.user.service.UserPreCreatingHandler;

/**
 * Fills a freshly created Halo account with the profile BlogsClub returned, so a blogger signing in
 * for the first time never has to type or verify an e-mail address.
 *
 * <p>Halo provisions the account inside its own OAuth2 filter chain and only afterwards invokes the
 * {@link UserPreCreatingHandler} extension point. That hook receives the {@link User} but not the
 * OAuth2 principal, and the username Halo generates is random ({@code user-ylitqejf}), so the
 * principal cannot be looked up by name. The attributes are therefore parked by the authentication
 * filter and claimed here by matching whatever the new account already carries: the display name,
 * the avatar, or — most reliably — the fact that it is the only unclaimed profile.</p>
 *
 * <p>Entries are removed as soon as they are claimed and expire on their own, so the map stays
 * small. Only blank fields are filled; anything already set is left untouched.</p>
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogsClubUserPreCreatingHandler implements UserPreCreatingHandler {

    /** Upper bound on parked profiles; protects against unbounded growth under abuse. */
    private static final int MAX_CACHE_SIZE = 256;

    /** A parked profile older than this can no longer belong to the account being created. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final Map<String, ParkedProfile> pendingProfiles = new ConcurrentHashMap<>();

    private record ParkedProfile(Map<String, Object> attributes, long parkedAt) {

        boolean isExpired() {
            return System.currentTimeMillis() - parkedAt > MAX_AGE.toMillis();
        }
    }

    /**
     * Parks the BlogsClub attributes of an identity that has just authenticated, so the account
     * Halo is about to create can pick them up.
     *
     * @param registrationId OAuth2 registration id; only BlogsClub is relevant
     * @param principal     the authenticated BlogsClub principal
     */
    public void remember(String registrationId, OAuth2User principal) {
        if (!BlogsClubClientRegistrationRepository.REGISTRATION_ID.equals(registrationId)
            || principal == null) {
            return;
        }
        String openId = principal.getName();
        if (!StringUtils.hasText(openId)) {
            return;
        }
        evictExpired();
        if (pendingProfiles.size() >= MAX_CACHE_SIZE) {
            // Drop the oldest entry so a burst of concurrent logins still gets handled.
            pendingProfiles.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().parkedAt()))
                .map(Map.Entry::getKey)
                .ifPresent(pendingProfiles::remove);
        }
        pendingProfiles.put(openId,
            new ParkedProfile(Map.copyOf(principal.getAttributes()), System.currentTimeMillis()));
        log.info("[BlogsClub] 已缓存 BlogsClub 资料，等待建号时回填：openid={}", openId);
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
     * Finds the parked profile that belongs to the account currently being created. Halo names the
     * account randomly, so matching relies on data the account already carries. A single parked
     * profile is unambiguous and is the common case (one login at a time).
     */
    private Map<String, Object> claim(User user) {
        evictExpired();
        if (pendingProfiles.isEmpty()) {
            return null;
        }
        String displayName = user.getSpec().getDisplayName();
        String email = user.getSpec().getEmail();
        for (Map.Entry<String, ParkedProfile> entry : pendingProfiles.entrySet()) {
            var attributes = entry.getValue().attributes();
            if (matches(displayName, attributes.get("blog_name"))
                || matches(email, attributes.get("email"))
                || matches(email, attributes.get("openid"))) {
                pendingProfiles.remove(entry.getKey());
                log.info("[BlogsClub] 建号时匹配到 BlogsClub 资料：openid={}", entry.getKey());
                return attributes;
            }
        }
        if (pendingProfiles.size() == 1) {
            var only = pendingProfiles.entrySet().iterator().next();
            pendingProfiles.remove(only.getKey());
            log.info("[BlogsClub] 建号时按唯一待处理资料回填：openid={}", only.getKey());
            return only.getValue().attributes();
        }
        return null;
    }

    private static boolean matches(String actual, Object candidate) {
        return StringUtils.hasText(actual) && candidate != null
            && actual.equalsIgnoreCase(String.valueOf(candidate));
    }

    private void evictExpired() {
        List<String> expired = pendingProfiles.entrySet().stream()
            .filter(e -> e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .toList();
        expired.forEach(pendingProfiles::remove);
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