package io.github.ybrc114514.blogsclub;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Persisted OAuth2 authorized client for the BlogsClub provider.
 *
 * <p>This extension deliberately uses its <em>own</em> group ({@code blogsclub.oauth.ybrc114514.io})
 * instead of the official OAuth2 plugin's {@code oauth.halo.run} group. Halo's {@code SchemeManager}
 * keys schemes by GVK, so sharing a GVK means the last plugin to start wins and the first plugin to
 * stop tears down a resource type the other one is still using — breaking token persistence for
 * every other OAuth2 login method on the site. A private group makes the two plugins fully
 * independent, regardless of start order.</p>
 *
 * @since 1.0.0
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(group = "blogsclub.oauth.ybrc114514.io", version = "v1alpha1", kind = "BlogsClubAuthorizedClient",
    plural = "blogsclubauthorizedclients", singular = "blogsclubauthorizedclient")
public class BlogsClubAuthorizedClient extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private BlogsClubAuthorizedClientSpec spec;

    @Data
    @ToString
    public static class BlogsClubAuthorizedClientSpec {

        @Schema(requiredMode = REQUIRED)
        private String registrationId;

        @Schema(requiredMode = REQUIRED)
        private String principalName;

        @Schema(requiredMode = REQUIRED)
        private String accessTokenType;

        @Schema(requiredMode = REQUIRED)
        private String accessTokenValue;

        @Schema(requiredMode = REQUIRED)
        private Instant accessTokenIssuedAt;

        @Schema(requiredMode = REQUIRED)
        private Instant accessTokenExpiresAt;

        private String accessTokenScopes;

        private String refreshTokenValue;

        private Instant refreshTokenIssuedAt;
    }
}
