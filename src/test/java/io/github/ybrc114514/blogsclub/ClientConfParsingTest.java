package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import run.halo.app.infra.utils.JsonUtils;

/**
 * Regression tests for the AppID / AppKey configuration parsing.
 *
 * <p>An empty configuration used to be rejected inside the record constructor, which Jackson
 * wrapped in a {@code JsonParseException} — the operator then saw nothing but a blank HTTP 500.</p>
 */
class ClientConfParsingTest {

    @Test
    void anEmptyConfigNoLongerExplodesInsideJackson() {
        assertThatCode(() -> JsonUtils.jsonToObject("{}",
            BlogsClubClientRegistrationRepository.ClientConf.class))
            .doesNotThrowAnyException();

        var conf = JsonUtils.jsonToObject("{}",
            BlogsClubClientRegistrationRepository.ClientConf.class);
        assertThat(conf.clientId()).isNull();
        assertThat(conf.clientSecret()).isNull();
    }

    @Test
    void readsBothCredentials() {
        var conf = JsonUtils.jsonToObject(
            "{\"clientId\":\"my-app-id\",\"clientSecret\":\"my-app-key\"}",
            BlogsClubClientRegistrationRepository.ClientConf.class);

        assertThat(conf.clientId()).isEqualTo("my-app-id");
        assertThat(conf.clientSecret()).isEqualTo("my-app-key");
    }
}
