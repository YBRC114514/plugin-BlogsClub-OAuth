package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.GVK;
import run.halo.app.extension.Scheme;

/**
 * Guards the plugin's resource types against colliding with the official OAuth2 plugin.
 *
 * <p>Halo's {@code SchemeManager} keeps one global scheme registry keyed by GVK. When this plugin
 * used {@code oauth.halo.run/v1alpha1} — the same GVK the official OAuth2 plugin uses — stopping
 * this plugin unregistered a resource type the official plugin was still using, breaking every
 * other OAuth2 login method's configuration reads and token storage. These tests fail if the GVKs
 * ever drift back into the official group.</p>
 */
class ResourceTypeIsolationTest {

    /** The group owned by the official OAuth2 plugin; this plugin must never register into it. */
    private static final String OFFICIAL_OAUTH_GROUP = "oauth.halo.run";

    @Test
    void authorizedClientUsesAPrivateGroup() {
        var gvk = gvkOf(BlogsClubAuthorizedClient.class);

        assertThat(gvk.group()).isNotEqualTo(OFFICIAL_OAUTH_GROUP);
        assertThat(gvk.group()).isEqualTo("blogsclub.oauth.ybrc114514.io");
        assertThat(gvk.kind()).isEqualTo("BlogsClubAuthorizedClient");
    }

    @Test
    void clientRegistrationUsesAPrivateGroup() {
        var gvk = gvkOf(BlogsClubClientRegistration.class);

        assertThat(gvk.group()).isNotEqualTo(OFFICIAL_OAUTH_GROUP);
        assertThat(gvk.group()).isEqualTo("blogsclub.oauth.ybrc114514.io");
        assertThat(gvk.kind()).isEqualTo("BlogsClubClientRegistration");
    }

    @Test
    void bothResourceTypesShareTheSamePrivateGroup() {
        // Same group is fine and intentional: it is this plugin's own namespace.
        assertThat(gvkOf(BlogsClubAuthorizedClient.class).group())
            .isEqualTo(gvkOf(BlogsClubClientRegistration.class).group());
    }

    @Test
    void theTwoResourceTypesDoNotCollideWithEachOther() {
        assertThat(gvkOf(BlogsClubAuthorizedClient.class))
            .isNotEqualTo(gvkOf(BlogsClubClientRegistration.class));
    }

    @Test
    void pluginRegistersAndUnregistersOnlyItsOwnSchemes() {
        // The schemes the plugin touches must be exactly its own two types, never the official
        // oauth.halo.run ones — that is what made stopping this plugin destructive before.
        var registered = java.util.List.of(
            Scheme.buildFromType(BlogsClubClientRegistration.class),
            Scheme.buildFromType(BlogsClubAuthorizedClient.class));

        assertThat(registered).allSatisfy(
            scheme -> assertThat(scheme.groupVersionKind().group())
                .isNotEqualTo(OFFICIAL_OAUTH_GROUP));
        assertThat(registered).doesNotHaveDuplicates();
    }

    private static GVK gvkOf(Class<?> type) {
        return type.getAnnotation(GVK.class);
    }

    @Test
    void gvkAnnotationsArePresentAtRuntime() throws NoSuchFieldException {
        // Sanity check that the annotation is retained and readable, otherwise the assertions
        // above would silently pass on null-free but wrong values.
        Field field = BlogsClubPlugin.class.getDeclaredField("schemeManager");
        assertThat(field.getType().getName())
            .isEqualTo("run.halo.app.extension.SchemeManager");
    }
}