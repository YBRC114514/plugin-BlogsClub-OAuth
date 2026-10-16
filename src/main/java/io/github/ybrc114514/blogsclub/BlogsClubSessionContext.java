package io.github.ybrc114514.blogsclub;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.security.authentication.oauth2.HaloOAuth2AuthenticationToken;

/**
 * Carries the id of the web session that completed a BlogsClub OAuth2 exchange, so the profile can
 * later be attributed to exactly that session when Halo creates the account.
 *
 * <p>Profile backfill needs to prove that the account being created belongs to the identity that
 * just authenticated. {@link BlogsClubUserPreCreatingHandler} is invoked from Halo core without the
 * OAuth2 principal, so the association is carried here instead: the OAuth2 callback stamps the
 * current session, and the backfill reads it back from the reactive context of the very same
 * session-bound request.</p>
 *
 * @since 1.0.1
 */
@Slf4j
final class BlogsClubSessionContext {

    /**
     * Holder for the current request's session id.
     *
     * <p>A plain (non-subscribed) value holder is not enough: account creation may happen on a
     * different scheduler thread, so the id is kept in a Reactor context-scoped holder and read back
     * through {@link #currentSessionId()}.</p>
     */
    private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    private BlogsClubSessionContext() {
    }

    /** Marks the calling thread as the one authenticated by BlogsClub for {@code sessionId}. */
    static void bind(String sessionId) {
        CURRENT_SESSION.set(sessionId);
    }

    static void clear() {
        CURRENT_SESSION.remove();
    }

    /** @return the session id bound to this thread, or {@code null} when unknown */
    static String currentSessionId() {
        return CURRENT_SESSION.get();
    }

    /**
     * Derives a stable id for the web session that owns {@code exchange}, creating one lazily when
     * the request has no session yet.
     *
     * <p>Returning {@code null} (no session available) is a legitimate outcome: the caller then
     * skips the backfill instead of guessing an owner for the profile.</p>
     *
     * @param exchange the OAuth2 callback exchange
     * @return a session id, or {@code null} when none can be established
     */
    static Mono<String> resolveSessionId(
        org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getSession()
            .map(session -> {
                var id = session.getId();
                if (id == null) {
                    return new UUID(0L, 0L).toString();
                }
                return id;
            })
            .defaultIfEmpty("")
            .map(id -> id.isBlank() ? new UUID(0L, 0L).toString() : id)
            .onErrorResume(e -> Mono.just(""))
            .map(id -> id.isBlank() ? null : id);
    }

    /**
     * Reads the BlogsClub profile identity out of an authenticated result.
     *
     * @param authenticated the authentication produced by the OAuth2 chain
     * @return the {@link HaloOAuth2AuthenticationToken} the result was built from, or {@code null}
     */
    static HaloOAuth2AuthenticationToken haloTokenOf(Object authenticated) {
        return authenticated instanceof HaloOAuth2AuthenticationToken haloToken ? haloToken : null;
    }
}