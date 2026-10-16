package io.github.ybrc114514.blogsclub;

import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;

/**
 * BlogsClub authentication plugin for Halo.
 *
 * <p>Registers the two extension types this plugin owns — the BlogsClub client registration and
 * the persisted authorized client — under the plugin-private
 * {@code blogsclub.oauth.ybrc114514.io} group.</p>
 *
 * <p>They must <em>not</em> reuse the official OAuth2 plugin's {@code oauth.halo.run} group. Halo
 * keys schemes by GVK in a single global registry, so two plugins sharing a GVK interfere with each
 * other: stopping one calls {@link SchemeManager#unregister(Scheme)}, which removes the resource
 * type the other plugin is still using, breaking its configuration lookups and token persistence.
 * With a private group, each plugin registers and unregisters only what it owns, so the official
 * OAuth2 plugin can be started, stopped, or run alongside this one in any order.</p>
 *
 * @since 1.0.0
 */
@Component
public class BlogsClubPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public BlogsClubPlugin(SchemeManager schemeManager) {
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(BlogsClubClientRegistration.class);
        schemeManager.register(BlogsClubAuthorizedClient.class);
    }

    @Override
    public void stop() {
        // Only unregister the schemes this plugin registered. Because they live in a private GVK
        // group, removing them can never affect the official OAuth2 plugin's resource types.
        schemeManager.unregister(Scheme.buildFromType(BlogsClubClientRegistration.class));
        schemeManager.unregister(Scheme.buildFromType(BlogsClubAuthorizedClient.class));
    }

    /**
     * Exposes {@link BlogsClubPlugin} as a bean so the scheme lifecycle is driven by Halo's plugin
     * runtime.
     *
     * @param schemeManager Halo's global scheme registry
     * @return the plugin
     */
    @Bean
    BlogsClubPlugin blogsClubPlugin(SchemeManager schemeManager) {
        return new BlogsClubPlugin(schemeManager);
    }
}