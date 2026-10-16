package io.github.ybrc114514.blogsclub;

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
 * <p>Like {@link BlogsClubAuthorizedClient}, this extension uses its own group instead of the
 * official OAuth2 plugin's {@code oauth.halo.run} group. Halo's {@code SchemeManager} keys schemes by
 * GVK, so reusing the official group would let the two plugins clobber each other's registration:
 * stopping one unregisters a resource type the other still depends on. A private group keeps the
 * plugins independent no matter which starts or stops first.</p>
 *
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "blogsclub.oauth.ybrc114514.io", version = "v1alpha1",
    kind = "BlogsClubClientRegistration",
    singular = "blogsclubclientregistration", plural = "blogsclubclientregistrations")
public class BlogsClubClientRegistration extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private BlogsClubClientRegistrationSpec spec;

    @Data
    @ToString
    public static class BlogsClubClientRegistrationSpec {

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
