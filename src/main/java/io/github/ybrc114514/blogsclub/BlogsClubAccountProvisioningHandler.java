package io.github.ybrc114514.blogsclub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.UserConnection;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.security.authentication.oauth2.HaloOAuth2AuthenticationToken;

/**
 * Fills the gap Halo leaves in its OAuth2 handling: an account for a first-time BlogsClub blogger.
 *
 * <p>Halo's {@code MapOAuth2AuthenticationFilter} resolves an authentication to an existing
 * {@code UserConnection}. When there is none it saves the token, logs the request out and redirects to
 * {@code /login?oauth2_bind} — a manual binding flow, not the "sign in and get an account" behaviour
 * this plugin advertises. This collaborator supplies the missing step and is invoked from the
 * plugin's own {@link BlogsClubAuthenticationWebFilter}.</p>
 *
 * <p>It has to run from the plugin's own filter rather than by implementing Halo's
 * {@code LoginHandlerEnhancer}: that interface is not a pf4j extension point, and Halo resolves a
 * single main-context bean for it, so a plugin implementation would never be called. The filter
 * still forwards to Halo's enhancer afterwards, which keeps remember-me, device management and
 * Halo's own login bookkeeping working.</p>
 *
 * <p>Guards that keep this from doing anything unexpected:</p>
 * <ul>
 *   <li>Only BlogsClub identities are touched — the registration id has to match, so no other
 *       provider's sign-in is affected.</li>
 *   <li>{@link BlogsClubAccountProvisioner} honours the site's registration policy, so a site with
 *       registration closed gets no new accounts.</li>
 *   <li>A visitor who is already signed in never gets a second account — Halo core binds the
 *       BlogsClub identity to the existing one instead (see
 *       {@link #provisionIfUnbound(Authentication, Authentication)}).</li>
 * </ul>
 *
 * <p>Provisioning failure is never fatal: the visitor falls back to Halo's binding flow, so a
 * misconfiguration cannot turn a sign-in button into a broken page.</p>
 *
 * @since 1.1.0
 */
@Slf4j
@Component
public class BlogsClubAccountProvisioningHandler {

    /**
     * Same trust resolver core uses to decide whether an authentication counts as a sign-in
     * (anonymous and remember-me tokens do not).
     */
    private static final org.springframework.security.authentication.AuthenticationTrustResolver
        TRUST_RESOLVER = new org.springframework.security.authentication
        .AuthenticationTrustResolverImpl();

    private final ReactiveExtensionClient client;

    private final BlogsClubAccountProvisioner provisioner;

    public BlogsClubAccountProvisioningHandler(ReactiveExtensionClient client,
        BlogsClubAccountProvisioner provisioner) {
        this.client = client;
        this.provisioner = provisioner;
    }

    /**
     * Creates an account when {@code authentication} is a BlogsClub identity with no binding yet
     * and the visitor is not signed in.
     *
     * <p>A visitor who already has an account is deliberately <em>not</em> provisioned for:
     * Halo core's {@code MapOAuth2AuthenticationFilter} binds an unbound identity to exactly the
     * pre-authenticated user (it caches that authentication before the chain runs and calls
     * {@code createUserConnection} for it), which is the official "bind to my account" behaviour
     * the user centre's 绑定 button starts. Creating a second account here shadowed it and left
     * the blogger signed in as a brand-new, empty account instead of the one they asked to bind
     * (production incident 2026-10-17: every bind attempt produced another {@code user-xxxxxx}).</p>
     *
     * @param authentication     the authentication produced by the BlogsClub callback
     * @param preAuthentication  the visitor's sign-in captured before the OAuth2 token replaced
     *                           the security context, or {@code null} for an anonymous visitor
     * @return the created account, or empty when nothing was provisioned
     */
    public Mono<User> provisionIfUnbound(Authentication authentication,
        @Nullable Authentication preAuthentication) {
        if (!isBlogsClub(authentication)) {
            return Mono.empty();
        }
        if (isSignedIn(preAuthentication)) {
            log.info("[BlogsClub] 已登录用户 {}，跳过自动建号，交由 Halo 将 BlogsClub 身份绑定到该账号",
                preAuthentication.getName());
            return Mono.empty();
        }
        var token = authentication instanceof HaloOAuth2AuthenticationToken haloToken
            ? haloToken.getOriginal()
            : (OAuth2AuthenticationToken) authentication;
        OAuth2User principal = token.getPrincipal();
        String openId = principal == null ? null : principal.getName();
        if (!StringUtils.hasText(openId)) {
            return Mono.empty();
        }
        return isBound(openId)
            .filter(bound -> !bound)
            .flatMap(ignored -> provisioner.provision(principal, openId))
            .onErrorResume(error -> {
                log.error("[BlogsClub] 自动建号流程出错，已回退到 Halo 默认的账号绑定流程", error);
                return Mono.empty();
            });
    }

    /**
     * Whether the visitor arrived with an existing sign-in.
     *
     * <p>Uses Spring Security's {@link AuthenticationTrustResolver} for exactly the semantics
     * Halo core's {@code MapOAuth2AuthenticationFilter} applies to its pre-authentication:
     * anonymous and remember-me tokens report {@code isAuthenticated() == true} but are not
     * sign-ins, so they still get the anonymous first-login provisioning.</p>
     */
    private static boolean isSignedIn(@Nullable Authentication authentication) {
        return authentication != null && TRUST_RESOLVER.isAuthenticated(authentication);
    }

    /**
     * Unwraps Halo's token wrapper and confirms the identity really came from BlogsClub. Anything
     * else returns {@code false} without touching a single account.
     */
    private static boolean isBlogsClub(Authentication authentication) {
        if (authentication instanceof HaloOAuth2AuthenticationToken haloToken) {
            var original = haloToken.getOriginal();
            return original != null
                && isBlogsClub(original.getAuthorizedClientRegistrationId());
        }
        if (authentication instanceof OAuth2AuthenticationToken token) {
            return isBlogsClub(token.getAuthorizedClientRegistrationId());
        }
        return false;
    }

    private static boolean isBlogsClub(String registrationId) {
        return BlogsClubClientRegistrationRepository.REGISTRATION_ID.equals(registrationId);
    }

    /**
     * Whether this BlogsClub openid is already bound to an account.
     *
     * <p>{@code UserConnectionService} lives in Halo's application module and is not exposed to
     * plugins, so the binding is looked up through the extension client. The scan happens in memory
     * rather than as an indexed query on purpose: a site has one {@code UserConnection} per linked
     * identity so the list stays tiny, and the indexed query builders ({@code QueryFactory}) are
     * marked for removal in Halo, which would tie the plugin to an API it cannot avoid.</p>
     *
     * <p>An empty result means "provably unbound" and is the only value that allows provisioning.
     * A lookup failure completes empty as well, because creating a second account for an identity
     * that may already be bound would leave duplicate accounts behind — the visitor falling back to
     * the binding flow is the better outcome.</p>
     */
    private Mono<Boolean> isBound(String openId) {
        return client.listAll(UserConnection.class, ListOptions.builder().build(), Sort.unsorted())
            .any(connection -> {
                var spec = connection.getSpec();
                return spec != null
                    && isBlogsClub(spec.getRegistrationId())
                    && openId.equals(spec.getProviderUserId());
            })
            .onErrorResume(error -> {
                log.error("[BlogsClub] 查询 BlogsClub 绑定关系失败，已放弃自动建号以免产生重复账号",
                    error);
                return Mono.just(true);
            });
    }
}
