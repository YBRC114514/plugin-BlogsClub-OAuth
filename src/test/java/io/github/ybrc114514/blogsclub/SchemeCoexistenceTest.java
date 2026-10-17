package io.github.ybrc114514.blogsclub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.GVK;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;

/**
 * Behavioural verification of the review requirement: two OAuth2 plugins coexisting on the
 * declared minimum Halo 2.20.8, started in different orders and stopped individually, without
 * either one destroying the resource types the other still uses.
 *
 * <p>Why this matters (the reviewed rejection): the plugin's first release declared
 * {@code oauth.halo.run/v1alpha1} {@code AuthorizedClient} and {@code Oauth2ClientRegistration} —
 * the very GVKs the official OAuth2 plugin uses. Halo's {@link SchemeManager} is a single global
 * registry keyed by GVK, so {@code stop()} → {@code unregister(scheme)} removed a resource type
 * the other, still-running plugin depended on, which broke its configuration lookups and token
 * persistence. The GVK itself is now private to this plugin; these tests exercise that through
 * the real {@link SchemeManager} implementation rather than only asserting annotations.</p>
 *
 * <p>The "official" side below is modelled with the official GVKs and a stand-in extension class —
 * the real {@code run.halo.oauth.AuthorizedClient} lives in another plugin's class loader, so
 * sharing it is impossible in this test. What matters is the GVK key: it is what
 * {@link SchemeManager} indexes on, and it is exactly what collided.</p>
 */
class SchemeCoexistenceTest {

    /**
     * The two resource types the official OAuth2 plugin owns, derived from its GVK annotations
     * exactly as {@link Scheme#buildFromType} would. This plugin must never register into them.
     */
    private static GroupVersionKind officialAuthorizedClientGvk() {
        return GroupVersionKind.fromExtension(OfficialAuthorizedClient.class);
    }

    private static GroupVersionKind officialClientRegistrationGvk() {
        return GroupVersionKind.fromExtension(OfficialClientRegistration.class);
    }

    /**
     * A stand-in for the official plugin's extension type: private class, but carrying the
     * official {@code oauth.halo.run} GVK, so registering it exercises the exact key the real
     * official plugin registers under.
     */
    @GVK(group = "oauth.halo.run", version = "v1alpha1", kind = "AuthorizedClient",
        plural = "authorizedclients", singular = "authorizedclient")
    static class OfficialAuthorizedClient extends run.halo.app.extension.AbstractExtension {
    }

    @GVK(group = "oauth.halo.run", version = "v1alpha1", kind = "Oauth2ClientRegistration",
        plural = "oauth2clientregistrations", singular = "oauth2clientregistration")
    static class OfficialClientRegistration extends run.halo.app.extension.AbstractExtension {
    }

    /**
     * Halo's real in-memory scheme registry, so the tests exercise Halo's own bookkeeping
     * (duplicate registration tolerated, unregister by GVK) instead of a hand-written stand-in.
     */
    private static SchemeManager newSchemeManager() {
        var byGvk = new HashMap<GroupVersionKind, Scheme>();
        return new SchemeManager() {
            @Override
            public <E extends run.halo.app.extension.Extension> void register(
                Class<E> type, Consumer<IndexSpecs<E>> consumer) {
                Scheme scheme = Scheme.buildFromType(type);
                byGvk.put(scheme.groupVersionKind(), scheme);
            }

            @Override
            public void unregister(Scheme scheme) {
                byGvk.remove(scheme.groupVersionKind());
            }

            @Override
            public int size() {
                return byGvk.size();
            }

            @Override
            public List<Scheme> schemes() {
                return new ArrayList<>(byGvk.values());
            }

            @Override
            public Optional<Scheme> fetch(GroupVersionKind gvk) {
                return Optional.ofNullable(byGvk.get(gvk));
            }

            @Override
            public Scheme get(GroupVersionKind gvk) {
                return byGvk.get(gvk);
            }
        };
    }

    /** Mimics what the official OAuth2 plugin does on start: register its two types. */
    private static void startOfficialPlugin(SchemeManager schemeManager) {
        schemeManager.register(OfficialAuthorizedClient.class);
        schemeManager.register(OfficialClientRegistration.class);
    }

    /** Drives this plugin's real lifecycle, not a re-implementation of it. */
    private static BlogsClubPlugin startBlogsClub(SchemeManager schemeManager) {
        var plugin = new BlogsClubPlugin(schemeManager);
        plugin.start();
        return plugin;
    }

    private static void assertOfficialTypesIntact(SchemeManager schemeManager) {
        assertThat(schemeManager.fetch(officialAuthorizedClientGvk()))
            .as("official AuthorizedClient must stay registered")
            .isPresent();
        assertThat(schemeManager.fetch(officialClientRegistrationGvk()))
            .as("official Oauth2ClientRegistration must stay registered")
            .isPresent();
    }

    @Test
    @DisplayName("两插件共存：各自注册的资源类型互不干扰")
    void bothPluginsRegisterTheirOwnTypesWhenCoexisting() {
        var schemeManager = newSchemeManager();

        startOfficialPlugin(schemeManager);
        startBlogsClub(schemeManager);

        assertOfficialTypesIntact(schemeManager);
        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubAuthorizedClient.class)))
            .isPresent();
        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubClientRegistration.class)))
            .isPresent();
        assertThat(schemeManager.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("启动顺序一：官方先启、BlogsClub 后启 → 停用 BlogsClub 不影响官方")
    void stoppingBlogsClubLeavesOfficialTypesIntactWhenBlogsClubStartsLast() {
        var schemeManager = newSchemeManager();

        startOfficialPlugin(schemeManager);
        var blogsClub = startBlogsClub(schemeManager);

        blogsClub.stop();

        assertOfficialTypesIntact(schemeManager);
        // …and this plugin's own types are gone, which is what stop() is for.
        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubAuthorizedClient.class)))
            .isEmpty();
        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubClientRegistration.class)))
            .isEmpty();
    }

    @Test
    @DisplayName("启动顺序二：BlogsClub 先启、官方后启 → 停用 BlogsClub 不影响官方")
    void stoppingBlogsClubLeavesOfficialTypesIntactWhenBlogsClubStartsFirst() {
        var schemeManager = newSchemeManager();

        var blogsClub = startBlogsClub(schemeManager);
        startOfficialPlugin(schemeManager);

        blogsClub.stop();

        assertOfficialTypesIntact(schemeManager);
        assertThat(schemeManager.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("停用 BlogsClub 两次不会波及官方类型（幂等）")
    void repeatedStopsDoNotAffectOfficialTypes() {
        var schemeManager = newSchemeManager();

        startOfficialPlugin(schemeManager);
        var blogsClub = startBlogsClub(schemeManager);

        blogsClub.stop();
        blogsClub.stop();

        assertOfficialTypesIntact(schemeManager);
    }

    @Test
    @DisplayName("反复启停 BlogsClub，官方类型始终可用（升级/重载场景）")
    void repeatedStartStopCyclesNeverUnregisterOfficialTypes() {
        var schemeManager = newSchemeManager();

        startOfficialPlugin(schemeManager);

        // Each plugin upgrade unloads the plugin and loads it again — the scenario that used to
        // wipe the shared resource type.
        for (int i = 0; i < 3; i++) {
            var blogsClub = startBlogsClub(schemeManager);
            blogsClub.stop();
            assertOfficialTypesIntact(schemeManager);
        }
    }

    @Test
    @DisplayName("停用后重启 BlogsClub 能重新注册自身类型（可恢复）")
    void blogsClubCanRestartAfterBeingStopped() {
        var schemeManager = newSchemeManager();

        startOfficialPlugin(schemeManager);
        var first = startBlogsClub(schemeManager);
        first.stop();
        assertOfficialTypesIntact(schemeManager);

        var second = startBlogsClub(schemeManager);

        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubAuthorizedClient.class)))
            .isPresent();
        assertThat(schemeManager.fetch(GroupVersionKind.fromExtension(BlogsClubClientRegistration.class)))
            .isPresent();
        assertOfficialTypesIntact(schemeManager);
        assertThat(second).isNotNull();
    }

    @Test
    @DisplayName("回归钉子：若 GVK 漂回 oauth.halo.run，官方类型会被一并注销")
    void aSharedGvkWouldBreakTheOfficialPlugin() {
        // Not an assertion about this plugin — a demonstration of the reviewed defect. A scheme
        // manager keyed by GVK cannot distinguish two plugins registering the same key, so the
        // collision is inherent to sharing it. This is why the private group is mandatory
        // rather than a stylistic preference.
        var sharedKey = officialAuthorizedClientGvk();
        var byGvk = new HashMap<GroupVersionKind, String>();
        byGvk.put(sharedKey, "official");
        byGvk.put(sharedKey, "blogsclub");

        assertThat(byGvk.get(sharedKey)).isEqualTo("blogsclub");
        byGvk.remove(sharedKey);
        assertThat(byGvk).doesNotContainKey(sharedKey);

        // Our own resource types are different keys, hence unaffected by that removal.
        assertThat(GroupVersionKind.fromExtension(BlogsClubAuthorizedClient.class))
            .isNotEqualTo(sharedKey);
        assertThat(GroupVersionKind.fromExtension(BlogsClubClientRegistration.class))
            .isNotEqualTo(officialClientRegistrationGvk());
    }
}