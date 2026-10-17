package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
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
 *     <li>a profile is written only when it can be attributed to the exact account being created,
 *     so one blogger's e-mail can never land on another blogger's account;</li>
 *     <li>no field is guessed — an account with no parked profile is created untouched instead of
 *     with "whatever profile happens to be waiting".</li>
 * </ul>
 *
 * <p>The association is the username the provisioner generated and Halo carries on the very
 * {@code User} handed to {@link BlogsClubUserPreCreatingHandler#preCreating} — no session id and
 * no thread-local state, both of which a reactive thread hop could silently drop.</p>
 */
class BlogsClubUserPreCreatingHandlerTest {

    private final BlogsClubUserPreCreatingHandler handler =
        new BlogsClubUserPreCreatingHandler();

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

    /** Parks a profile under the username of the account it may be written onto. */
    private void remember(String username, Map<String, Object> attributes) {
        handler.remember(username, principal(attributes));
    }

    @Test
    @DisplayName("按建号用户名关联，回填邮箱、昵称与头像")
    void fillsEmailDisplayNameAndAvatarForTheParkedAccount() {
        remember("user-ylitqejf", Map.of(
            "openid", "abc123",
            "email", "ybrc@example.com",
            "blog_name", "拥抱日出",
            "blogger_avatar", "https://www.blogsclub.org/avatar.png"));

        // Halo generates a random user name, not the openid.
        var user = newUser("user-ylitqejf");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("ybrc@example.com");
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
        assertThat(user.getSpec().getAvatar()).isEqualTo("https://www.blogsclub.org/avatar.png");
    }

    @Test
    @DisplayName("另一个账号的资料绝不会被写到本账号上")
    void doesNotBackfillAnotherAccountsProfile() {
        // A different blogger got a different generated username.
        remember("user-otherblogger", Map.of("openid", "other", "email", "other@example.com"));

        var user = newUser("user-ylitqejf");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isNull();
    }

    @Test
    @DisplayName("没有暂存资料时不猜测，账号原样创建")
    void doesNotGuessWhenNothingWasParked() {
        var user = newUser("user-ylitqejf");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isNull();
    }

    @Test
    @DisplayName("并发建号时各账号只拿到各自暂存的资料")
    void doesNotCrossAttributeBetweenConcurrentAccounts() {
        remember("user-one", Map.of("openid", "one", "email", "one@example.com"));
        remember("user-two", Map.of("openid", "two", "email", "two@example.com"));

        var first = newUser("user-one");
        StepVerifier.create(handler.preCreating(first)).verifyComplete();
        var second = newUser("user-two");
        StepVerifier.create(handler.preCreating(second)).verifyComplete();

        assertThat(first.getSpec().getEmail()).isEqualTo("one@example.com");
        assertThat(second.getSpec().getEmail()).isEqualTo("two@example.com");
    }

    @Test
    @DisplayName("不会覆盖账号上已有的邮箱")
    void doesNotOverwriteExistingEmail() {
        remember("user-abc", Map.of(
            "openid", "abc123",
            "email", "from-blogsclub@example.com",
            "blog_name", "拥抱日出"));

        var user = newUser("user-abc");
        user.getSpec().setEmail("already-set@example.com");

        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isEqualTo("already-set@example.com");
    }

    @Test
    @DisplayName("资料只被消费一次")
    void consumesProfileOnlyOnce() {
        remember("user-first", Map.of("openid", "abc123", "email", "ybrc@example.com"));

        var first = newUser("user-first");
        StepVerifier.create(handler.preCreating(first)).verifyComplete();
        assertThat(first.getSpec().getEmail()).isEqualTo("ybrc@example.com");

        // A second account must not inherit the consumed profile, even though the first account's
        // username is now free again.
        var second = newUser("user-first");
        StepVerifier.create(handler.preCreating(second)).verifyComplete();
        assertThat(second.getSpec().getEmail()).isNull();
    }

    @Test
    @DisplayName("forget 后资料不再可被认领")
    void forgottenProfileIsNotClaimed() {
        remember("user-abc", Map.of("openid", "abc123", "email", "ybrc@example.com"));
        handler.forget("user-abc");

        var user = newUser("user-abc");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
    }

    @Test
    @DisplayName("空用户名或空身份的暂存请求被忽略")
    void ignoresBlankRememberRequests() {
        handler.remember("", principal(Map.of("openid", "abc123",
            "email", "ybrc@example.com")));
        handler.remember("user-blank", null);
        // An identity without a usable openid must not be parked either: the openid is what the
        // UserConnection binding is built on later.
        handler.remember("user-no-openid",
            principal(Map.of("openid", "", "email", "ybrc@example.com")));

        var user = newUser("user-blank");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();
        assertThat(user.getSpec().getEmail()).isNull();

        var noOpenIdUser = newUser("user-no-openid");
        StepVerifier.create(handler.preCreating(noOpenIdUser)).verifyComplete();
        assertThat(noOpenIdUser.getSpec().getEmail()).isNull();
    }

    @Test
    @DisplayName("畸形邮箱不会写入，但昵称仍会回填")
    void doesNotWriteMalformedEmail() {
        remember("user-abc", Map.of(
            "openid", "abc123",
            "email", "not-an-email",
            "blog_name", "拥抱日出"));

        var user = newUser("user-abc");
        StepVerifier.create(handler.preCreating(user)).verifyComplete();

        assertThat(user.getSpec().getEmail()).isNull();
        assertThat(user.getSpec().getDisplayName()).isEqualTo("拥抱日出");
    }
}
