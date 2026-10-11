package run.halo.plugin.blogsclub;

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
 * @since 1.0.0
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(group = "oauth.halo.run", version = "v1alpha1", kind = "AuthorizedClient",
    plural = "authorizedclients", singular = "authorizedclient")
public class AuthorizedClient extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private AuthorizedClientSpec spec;

    @Data
    @ToString
    public static class AuthorizedClientSpec {

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
