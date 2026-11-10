package com.os4.musiccover;

/**
 * One unit of what the module installs into a host process, installed and reported on its own
 * (architecture plan, stage 3). The list each process gets is {@link FeatureRegistry}'s; the
 * installing, the isolation and the status are {@link FeatureHost}'s.
 *
 * <p>Deliberately small. {@link #needsContainer()} is the only precondition there is today: the
 * clock container class, which a build that renamed it does not have. A second kind of need is
 * the time to generalise this, not before.
 */
interface Feature {

    /**
     * Stable across versions; what {@code op features} and the app's home page name. Written
     * {@code feature:xxx} - never in the shape of a hook id (HookIdTest would take it for one and
     * ask for it in hook-ids.txt), and not tied to the hook ids it installs: one installer can put
     * in hooks under several prefixes (HyperTweaks.systemUi has four).
     */
    String id();

    /** Skipped, and reported as such, when SystemUI's clock container class was not found. */
    boolean needsContainer();

    void install(Ctx c) throws Throwable;

    /**
     * What an install is given. Not a bare ClassLoader: the control-centre plugin is installed
     * from three places (HyperTweaks.plugin on its own package, an attempt inside
     * HyperTweaks.systemUi, and HotReload's backfill), held apart by HyperTweaks.sPluginHooked,
     * and its loader is not the host's.
     */
    final class Ctx {
        /** The process's own loader; null for the plugin's package, which has none of its own. */
        final ClassLoader host;
        /** The control-centre plugin's loader, on that package only; null everywhere else. */
        final ClassLoader plugin;

        Ctx(ClassLoader host, ClassLoader plugin) {
            this.host = host;
            this.plugin = plugin;
        }
    }

    interface Body {
        void install(Ctx c) throws Throwable;
    }

    static Feature of(String id, boolean needsContainer, Body body) {
        return new Feature() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean needsContainer() {
                return needsContainer;
            }

            @Override
            public void install(Ctx c) throws Throwable {
                body.install(c);
            }
        };
    }
}
