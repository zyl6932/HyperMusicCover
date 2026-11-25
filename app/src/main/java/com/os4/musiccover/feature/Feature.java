package com.os4.musiccover.feature;

/**
 * One unit of what the module installs into a host process, installed and reported on its own
 * (architecture plan, stage 3). The list each process gets is {@link FeatureRegistry}'s; the
 * installing, the isolation and the status are {@link FeatureHost}'s.
 *
 * <p>Deliberately small. {@link #needsContainer()} is the only precondition there is today: the
 * clock container class, which a build that renamed it does not have. A second kind of need is
 * the time to generalise this, not before.
 */
public interface Feature {

    /**
     * Stable across versions; what {@code op features} and the app's home page name. Written
     * {@code feature:xxx} - never in the shape of a hook id (HookIdTest would take it for one and
     * ask for it in hook-ids.txt), and not tied to the hook ids it installs: one installer can put
     * in hooks under several prefixes (HyperChanger.systemUi has four).
     */
    String id();

    /** Skipped, and reported as such, when SystemUI's clock container class was not found. */
    boolean needsContainer();

    void install(Ctx c) throws Throwable;

    /** What an install is given: the process's own class loader. */
    final class Ctx {
        public final ClassLoader host;

        Ctx(ClassLoader host) {
            this.host = host;
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
