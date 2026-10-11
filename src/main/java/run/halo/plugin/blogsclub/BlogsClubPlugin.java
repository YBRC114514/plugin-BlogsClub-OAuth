package run.halo.plugin.blogsclub;

import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import org.springframework.stereotype.Component;

/**
 * BlogsClub authentication plugin for Halo.
 *
 * <p>Registers the BlogsClub OAuth2 client registration scheme and the authorized
 * client scheme so that Halo core can resolve the BlogsClub identity provider and
 * persist authorized clients.</p>
 *
 * @since 1.0.0
 */
@Component
public class BlogsClubPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public BlogsClubPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(AuthorizedClient.class);
        schemeManager.register(BlogsClubOAuth2ClientRegistration.class);
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(AuthorizedClient.class));
        schemeManager.unregister(Scheme.buildFromType(BlogsClubOAuth2ClientRegistration.class));
    }
}
