package com.os4.musiccover;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs a {@link FeatureRegistry.Target}'s features one by one, and remembers how each went,
 * for {@code op features} and the app's home page (architecture plan, stage 3).
 *
 * <p>Two modes, after decision D of the plan:
 * <ul>
 *   <li>Cold start: each feature is caught on its own, recorded FAILED, and the next one is
 *   installed. A symbol one build lacks costs its own feature only.</li>
 *   <li>Hot-reload adoption: the first feature to throw is rethrown, and HotReload takes the
 *   generation inert as it always has. A half-installed generation left running is harder to
 *   read than one that waits for a restart.</li>
 * </ul>
 *
 * <p>Whether a feature "installed" cannot be read off whether it threw: nearly every installer
 * catches its own failures and logs them. Every hook goes through Xp.hook into Gen's ledger,
 * though, so the number a feature added is counted around its install; a feature that threw
 * nothing and hooked nothing is NO_HOOKS. A feature that got some of its hooks in reads as
 * INSTALLED - the count is reported next to it, not checked against an expected one.
 */
final class FeatureHost {
    private FeatureHost() {
    }

    enum State { INSTALLED, NO_HOOKS, FAILED, SKIPPED }

    static final class Result {
        final String target;
        final String id;
        final State state;
        final int hooks;
        final long ms;
        final String error;

        Result(String target, String id, State state, int hooks, long ms, String error) {
            this.target = target;
            this.id = id;
            this.state = state;
            this.hooks = hooks;
            this.ms = ms;
            this.error = error;
        }
    }

    /**
     * Every feature installed in this process by this generation, in install order. Keyed by
     * target and id rather than cleared per call: SystemUI's process can also be told about the
     * control-centre plugin's package, and that must not wipe SystemUI's own record.
     */
    private static final Map<String, Result> RESULTS = new LinkedHashMap<>();
    /** What the clock container lookup said; null where nothing asked (other processes). */
    private static volatile String sContainer;

    static void installAll(FeatureRegistry.Target t, Feature.Ctx ctx, boolean adopt)
            throws Throwable {
        Xp.log(Main.TAG + "loaded into " + t.name);
        Boolean container = null;
        try {
            for (Feature f : t.features()) {
                if (f.needsContainer()) {
                    // Looked up where the first feature that needs it comes, which is where
                    // installSystemUi used to look it up, so the log reads in the same order.
                    if (container == null) {
                        container = t.container != null && t.container.find(ctx);
                        sContainer = container ? "found" : "missing";
                    }
                    if (!container) {
                        record(t, f, State.SKIPPED, 0, 0L, null);
                        continue;
                    }
                }
                int before = Gen.hooks.size();
                long t0 = SystemClock.uptimeMillis();
                try {
                    f.install(ctx);
                } catch (Throwable e) {
                    record(t, f, State.FAILED, Gen.hooks.size() - before,
                            SystemClock.uptimeMillis() - t0, String.valueOf(e));
                    Xp.w(Main.TAG + f.id() + " FAILED: " + e);
                    if (adopt) throw e;
                    continue;
                }
                int added = Gen.hooks.size() - before;
                record(t, f, added > 0 ? State.INSTALLED : State.NO_HOOKS, added,
                        SystemClock.uptimeMillis() - t0, null);
            }
        } finally {
            // Gen's install-scope snapshot: after every feature, exactly once, and on the paths
            // that skipped or failed too - a dry run takes back whatever is not in it, and it
            // does not run this again to put it back.
            if (t.marksInstallScope) Gen.markInstallScope();
            Xp.log(Main.TAG + "features for " + t.name + ": " + summary(t.name));
        }
    }

    private static void record(FeatureRegistry.Target t, Feature f, State s, int hooks, long ms,
                               String error) {
        synchronized (RESULTS) {
            RESULTS.put(t.name + "/" + f.id(), new Result(t.name, f.id(), s, hooks, ms, error));
        }
    }

    private static List<Result> results() {
        synchronized (RESULTS) {
            return new ArrayList<>(RESULTS.values());
        }
    }

    private static String summary(String target) {
        int[] n = new int[State.values().length];
        int total = 0;
        for (Result r : results()) {
            if (!r.target.equals(target)) continue;
            n[r.state.ordinal()]++;
            total++;
        }
        StringBuilder b = new StringBuilder().append(total);
        for (State s : State.values()) {
            b.append(' ').append(s.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                    .append(n[s.ordinal()]);
        }
        if (sContainer != null) b.append(" container=").append(sContainer);
        return b.toString();
    }

    /**
     * The ids of SystemUI's own features that are not INSTALLED, for the app's home page. The
     * control-centre plugin's package is left out: when it is told about at all, its soft glass
     * may already be in from HyperTweaks.systemUi's attempt, and then it hooks nothing on
     * purpose - op features still lists it.
     */
    static String[] missing() {
        List<String> out = new ArrayList<>();
        for (Result r : results()) {
            if (r.target.equals("SystemUI") && r.state != State.INSTALLED) out.add(r.id);
        }
        return out.toArray(new String[0]);
    }

    /** {@code op features}: a header per target, then one line per feature. */
    static String describe() {
        int gen = HotReload.generation();
        StringBuilder b = new StringBuilder();
        String target = null;
        for (Result r : results()) {
            if (!r.target.equals(target)) {
                target = r.target;
                b.append("features ").append(target).append(": ").append(summary(target))
                        .append(" gen=").append(gen).append('\n');
            }
            b.append(r.id).append(' ').append(r.state);
            if (r.state != State.SKIPPED) {
                b.append(" hooks=").append(r.hooks).append(' ').append(r.ms).append("ms");
            }
            // A generation after a hot reload that skipped a feature the one before it had:
            // the old generation's hooks for it were unhooked with the rest of what was left
            // over (HandleStore.unhookRemaining), so it is off now, not just not reinstalled.
            if (r.state == State.SKIPPED && gen > 1) {
                b.append(" (the previous generation's hooks for it were taken down)");
            }
            if (r.error != null) b.append(' ').append(r.error);
            b.append('\n');
        }
        if (b.length() == 0) return "no features installed in this process";
        return b.toString();
    }
}
