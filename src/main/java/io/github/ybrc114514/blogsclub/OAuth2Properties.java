package io.github.ybrc114514.blogsclub;

import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.StringUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the BlogsClub OAuth2 plugin. Currently only an optional
 * outbound HTTP proxy is supported (useful when the Halo server cannot reach
 * {@code www.blogsclub.org} directly).
 *
 * @since 1.0.0
 */
@Data
@ConfigurationProperties("blogsclub.oauth2")
@Validated
public class OAuth2Properties implements Validator {

    @Valid
    @NestedConfigurationProperty
    private final Proxy proxy = new Proxy();

    @Override
    public boolean supports(Class<?> clazz) {
        return OAuth2Properties.class == clazz;
    }

    @Override
    public void validate(Object target, Errors errors) {
        var properties = (OAuth2Properties) target;
        var p = properties.getProxy();
        if (p.isEnabled()) {
            if (p.getPort() <= 0 || p.getPort() > 65535) {
                errors.rejectValue("proxy.port", "Invalid port number",
                    "Port must be between 1 and 65535");
            }
            if (!StringUtils.hasText(p.getHost())) {
                errors.rejectValue("proxy.host", "Host cannot be blank when proxy is enabled");
            }
            if (p.getConnectTimeoutMillis() != null && p.getConnectTimeoutMillis() <= 0) {
                errors.rejectValue("proxy.connectTimeoutMillis",
                    "Connect timeout must be greater than zero");
            }
        }
    }

    @Data
    public static class Proxy {

        private boolean enabled;

        private String host;

        private int port;

        private String username;

        private String password;

        private Long connectTimeoutMillis;
    }
}
