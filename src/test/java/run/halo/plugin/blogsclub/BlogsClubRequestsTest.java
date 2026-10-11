package run.halo.plugin.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * Regression tests for the request-path guard that keeps the plugin's error pages from hijacking
 * the whole site.
 *
 * <p>The plugin registers two web filters through Halo's {@code AuthenticationSecurityWebFilter} /
 * {@code HttpBasicSecurityWebFilter} extension points, both of which wrap the entire request chain.
 * Their {@code onErrorResume} handlers used to run unconditionally, so a request for a missing page
 * (a plain {@code NoResourceFoundException} / HTTP 404 raised inside the theme) was rendered as the
 * "BlogsClub 登录失败" page instead of the theme's own 404 page.</p>
 *
 * <p>The fix restricts the error handling to genuine BlogsClub requests, which is exactly what
 * these predicates decide.</p>
 */
class BlogsClubRequestsTest {

    @Test
    void recognizesTheBlogsClubCallback() {
        var exchange = exchange("https://blog.byside.top/login/oauth2/code/blogsclub?code=abc");

        assertThat(BlogsClubRequests.isCallback(exchange)).isTrue();
        assertThat(BlogsClubRequests.isAuthorization(exchange)).isFalse();
    }

    @Test
    void recognizesTheBlogsClubAuthorizationKickOff() {
        var exchange = exchange("https://blog.byside.top/oauth2/authorization/blogsclub?display=popup");

        assertThat(BlogsClubRequests.isAuthorization(exchange)).isTrue();
        assertThat(BlogsClubRequests.isCallback(exchange)).isFalse();
    }

    @Test
    void ignoresAMissingPageSoHaloCanRenderItsOwn404() {
        var exchange = exchange("https://blog.byside.top/1111");

        assertThat(BlogsClubRequests.isCallback(exchange)).isFalse();
        assertThat(BlogsClubRequests.isAuthorization(exchange)).isFalse();
    }

    @Test
    void ignoresOrdinarySiteTraffic() {
        for (var uri : new String[] {
            "/",
            "/archives",
            "/2026/10/11/hello-world/",
            "/login",
            "/uc/profile",
            "/apis/api.console.halo.run/v1alpha1/posts",
            "/themes/run-DnEkFU/css/style.css",
            "/oauth2/authorization/github",
            "/login/oauth2/code/github"}) {
            var exchange = exchange("https://blog.byside.top" + uri);

            assertThat(BlogsClubRequests.isCallback(exchange))
                .as("callback predicate for %s", uri).isFalse();
            assertThat(BlogsClubRequests.isAuthorization(exchange))
                .as("authorization predicate for %s", uri).isFalse();
        }
    }

    private static MockServerWebExchange exchange(String uri) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(uri).build());
    }
}