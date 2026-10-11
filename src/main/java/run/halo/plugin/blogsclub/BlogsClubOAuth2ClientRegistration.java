package run.halo.plugin.blogsclub;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * BlogsClub OAuth2 client registration extension. The static endpoints of the BlogsClub
 * open platform are stored here; the per-tenant {@code clientId} (AppID) and
 * {@code clientSecret} (AppKey) are provided through the {@link AuthProvider}'s
 * {@code configMapRef}, which is populated by the plugin's backend Setting form.
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "oauth.halo.run", version = "v1alpha1", kind = "Oauth2ClientRegistration",
    singular = "oauth2clientregistration", plural = "oauth2clientregistrations")
public class BlogsClubOAuth2ClientRegistration extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private BlogsClubOAuth2ClientRegistrationSpec spec;

    @Data
    @ToString
    public static class BlogsClubOAuth2ClientRegistrationSpec {

        private String clientAuthenticationMethod;

        private String authorizationGrantType;

        private String redirectUri;

        private Set<String> scopes;

        private String authorizationUri;

        @Schema(requiredMode = REQUIRED, minLength = 1)
        private String tokenUri;

        private String userInfoUri;

        private String userInfoAuthenticationMethod;

        private String userNameAttributeName;

        private String jwkSetUri;

        private String issuerUri;

        private Map<String, Object> configurationMetadata;

        private String clientName;
    }
}
