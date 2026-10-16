package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.User.UserSpec;
import run.halo.app.extension.Metadata;

/**
 * Tests for the profile backfill performed while Halo creates an account.
 *
 * <p>Two properties matter here and both were called out during app-store review:</p>
 * <ul>
 *     <li>a profile is written only when it can be attributed to the session creating the account,
 *     so one blogger's e-mail can never land on another blogger's account;</li>
 *     <li>no field is guessed — an unprovable association leaves the account untouched instead of
 *     falling back to "the only profile still waiting".</li>
 * </ul>
 */
class BlogsClubUserPreCreatingHandlerTest {

    private final BlogsClubUserPreCreatingHandler handler =
        new BlogsClubUserPreCreatingHandler();

    private static final String SESSION = "session-1";

    @AfterEach
    void clearSession() {
        BlogsClubSessionContext.clear();
    }

    private static OAuth2User principal(Map<String, Object> attributes) {
        return new DefaultOAuth2User(Set.of(), attributes, "openid");
    }

    private static User newUser(String name) {
        var user = new User();
        var metadata = new Metadata();
        metadata.setName(name);
        user.setMetadata(metadata);
        user.setSpec(new UserSpec());
        return user;
    }

    /** Parks a profile and marks it as belonging to {@code sessionId}. */
    private void remember(Map<String, Object> attributes, String sessionId) {
        handler.remember("blogsclub", principal(attributes), sessionId);
    }

    @Test
    void fillsEmailAndDisplayNameWhenSessionMatches() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of(
            "openid", "abc123",
            "email", "ybrc@example.com",
            "blog_name", "拥抱日出",
            "blogger_avatar", "https://www.blogsclub.org/avatar.png"), SESSION);

        // Halo generates a random user name, not the openid.
        var user = newUser("user-ylitqejf");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("ybrc@example.com");
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
        assertThat(user.getSpec().getAvatar()).isEqualTo("https://www.blogsclub.org/avatar.png");
    }

    @Test
    void doesNotBackfillFromAnotherSession() {
        // A different blogger authenticated in a different session.
        remember(Map.of("openid", "other", "email", "other@example.com"), "session-other");
        BlogsClubSessionContext.bind(SESSION);

        var user = newUser("user-ylitqejf");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isNull();
    }

    @Test
    void doesNotGuessWhenNoSessionIsBound() {
        remember(Map.of("openid", "abc123", "email", "ybrc@example.com"), SESSION);
        // preCreating invoked without an authenticated session: nothing can be attributed.
        var user = newUser("user-ylitqejf");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    void doesNotGuessWhenTwoProfilesShareTheSession() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of("openid", "one", "email", "one@example.com"), SESSION);
        remember(Map.of("openid", "two", "email", "two@example.com"), SESSION);

        var user = newUser("user-ylitqejf");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    void doesNotConsumeAnotherSessionsProfile() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of("openid", "other", "email", "other@example.com"), "session-other");

        StepVerifier.create(handler.preCreating(newUser("user-1"))).verifyComplete();

        // The other session's profile must still be claimable by its own owner.
        BlogsClubSessionContext.bind("session-other");
        var rightful = newUser("user-2");
        StepVerifier.create(handler.preCreating(rightful)).verifyComplete();
        assertThat(rightful.getSpec().getEmail()).isEqualTo("other@example.com");
    }

    @Test
    void doesNotOverwriteExistingEmail() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of(
            "openid", "abc123",
            "email", "from-blogsclub@example.com",
            "blog_name", "拥抱日出"), SESSION);

        var user = newUser("user-abc");
        user.getSpec().setEmail("already-set@example.com");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("already-set@example.com");
    }

    @Test
    void ignoresOtherRegistrationId() {
        BlogsClubSessionContext.bind(SESSION);
        handler.remember("github",
            principal(Map.of("openid", "abc123", "email", "ybrc@example.com")), SESSION);

        var user = newUser("user-abc");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    void ignoresProfileWithoutSessionId() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of("openid", "abc123", "email", "ybrc@example.com"), null);

        var user = newUser("user-abc");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    void doesNotWriteMalformedEmail() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of(
            "openid", "abc123",
            "email", "not-an-email",
            "blog_name", "拥抱日出"), SESSION);

        var user = newUser("user-abc");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
    }

    @Test
    void consumesProfileOnlyOnce() {
        BlogsClubSessionContext.bind(SESSION);
        remember(Map.of("openid", "abc123", "email", "ybrc@example.com"), SESSION);

        var first = newUser("user-first");
        StepVerifier.create(handler.preCreating(first)).verifyComplete();
        assertThat(first.getSpec().getEmail()).isEqualTo("ybrc@example.com");

        // A second account in the same session must not inherit the consumed profile.
        var second = newUser("user-second");
        StepVerifier.create(handler.preCreating(second)).verifyComplete();
        assertThat(second.getSpec().getEmail()).isNull();
    }
}