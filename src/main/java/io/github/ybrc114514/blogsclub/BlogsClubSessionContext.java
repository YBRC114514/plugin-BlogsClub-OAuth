package io.github.ybrc114514.blogsclub;

import reactor.core.publisher.Mono;

/**
 * Carries the id of the web session that completed a BlogsClub OAuth2 exchange, so the profile can
 * later be attributed to exactly that session when Halo creates the account.
 *
 * <p>Profile backfill needs to prove that the account being created belongs to the identity that
 * just authenticated. {@link BlogsClubUserPreCreatingHandler} is invoked from Halo core without the
 * OAuth2 principal, so the association is carried here instead: the OAuth2 callback stamps the
 * current session, and the backfill reads it back from the request thread.</p>
 *
 * <p><b>No fallback id is ever invented.</b> An earlier version returned an all-zero UUID when the
 * session id was unavailable, which made every session-less request look like the same session and
 * would have let one visitor's profile be written onto another's account. An unknown session now
 * yields {@code null}, and the caller skips the backfill instead.</p>
 *
 * @since 1.0.1
 */
final class BlogsClubSessionContext {

    /**
     * Session id for the current request thread.
     *
     * <p>Account creation happens synchronously inside the OAuth2 callback's reactive chain, which
     * stays on one thread, so a plain holder is enough. A {@code null} value means "no session was
     * bound", never "session unknown but assume the same one".</p>
     */
    private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

    private BlogsClubSessionContext() {
    }

    /** Marks the calling thread as the one authenticated by BlogsClub for {@code sessionId}. */
    static void bind(String sessionId) {
        if (sessionId == null) {
            CURRENT_SESSION.remove();
            return;
        }
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
     * <p>{@code null} is a legitimate outcome for a session-less or erroring request: the caller then
     * skips the profile backfill rather than attributing it to an arbitrary session.</p>
     *
     * @param exchange the OAuth2 callback exchange
     * @return a non-blank session id, or {@code null} when none can be established
     */
    static Mono<String> resolveSessionId(
        org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getSession()
            .mapNotNull(session -> session.getId())
            .filter(id -> !id.isBlank())
            // No fabricated fallback: an id we cannot read must never collide with another request.
            .onErrorResume(e -> Mono.empty());
    }
}
