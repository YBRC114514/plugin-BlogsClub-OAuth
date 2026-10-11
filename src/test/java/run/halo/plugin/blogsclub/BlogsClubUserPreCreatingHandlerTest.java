package run.halo.plugin.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.User.UserSpec;
import run.halo.app.extension.Metadata;

class BlogsClubUserPreCreatingHandlerTest {

    private final BlogsClubUserPreCreatingHandler handler =
        new BlogsClubUserPreCreatingHandler();

    private static OAuth2User principal(Map<String, Object> attributes) {
        return new DefaultOAuth2User(java.util.Set.of(), attributes, "openid");
    }

    private static User newUser(String name) {
        var user = new User();
        var metadata = new Metadata();
        metadata.setName(name);
        user.setMetadata(metadata);
        user.setSpec(new UserSpec());
        return user;
    }

    @Test
    void shouldFillEmailAndDisplayNameFromBlogsClubProfile() {
        handler.remember("blogsclub", principal(Map.of(
            "openid", "abc123",
            "email", "ybrc@example.com",
            "blog_name", "拥抱日出")));

        // Halo generates a random user name, not the openid.
        var user = newUser("user-ylitqejf");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("ybrc@example.com");
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
    }

    @Test
    void shouldNotOverwriteExistingEmail() {
        handler.remember("blogsclub", principal(Map.of(
            "openid", "abc123",
            "email", "from-blogsclub@example.com",
            "blog_name", "拥抱日出")));

        var user = newUser("user-abc");
        user.getSpec().setEmail("already-set@example.com");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("already-set@example.com");
    }

    @Test
    void shouldIgnoreOtherRegistrationId() {
        handler.remember("github", principal(Map.of(
            "openid", "abc123",
            "email", "ybrc@example.com")));

        var user = newUser("user-abc");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    void shouldNotWriteMalformedEmail() {
        handler.remember("blogsclub", principal(Map.of(
            "openid", "abc123",
            "email", "not-an-email",
            "blog_name", "拥抱日出")));

        var user = newUser("user-abc");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
    }

    @Test
    void shouldConsumeProfileOnlyOnce() {
        handler.remember("blogsclub", principal(Map.of(
            "openid", "abc123",
            "email", "ybrc@example.com")));

        var first = newUser("user-first");
        StepVerifier.create(handler.preCreating(first)).verifyComplete();
        assertThat(first.getSpec().getEmail()).isEqualTo("ybrc@example.com");

        // A second account must not inherit the already consumed profile.
        var second = newUser("user-second");
        StepVerifier.create(handler.preCreating(second)).verifyComplete();
        assertThat(second.getSpec().getEmail()).isNull();
    }
}