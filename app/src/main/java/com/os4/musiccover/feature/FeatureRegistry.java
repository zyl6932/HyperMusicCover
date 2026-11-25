package com.os4.musiccover.feature;

import com.os4.musiccover.HyperTweaks;
import com.os4.musiccover.Main;
import com.os4.musiccover.WallpaperProbe;
import com.os4.musiccover.lyrics.AppleLyrics;
import com.os4.musiccover.pickup.RearScreen;
import com.os4.musiccover.transit.AmapImmerse;
import com.os4.musiccover.transit.MetroCodeIsland;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the module installs where: the one table both entries read (architecture plan, stage 3).
 * A cold start looks a target up by the package LSPosed names (Main.onPackageLoaded); a
 * hot-reload adoption by the process it is running in (HotReload.install). The two are not the
 * same key: 高德's :locationservice and :widgetProvider are its package under other process names,
 * and a loader rather than a process (the control centre plugin's APK, which AmapFocus looks
 * through) is picked up by HotReload.backfill instead, which stays out of this table because what
 * it installs depends on what the generation before had.
 *
 * <p>Process names are written out as literals, not constants: HotReloadProcessTest reads them
 * off this file and holds them equal to HotReloadPolicy.SUPPORTED, since this class cannot be
 * loaded in a JVM test (Main needs the Xposed API).
 */
public final class FeatureRegistry {
    private FeatureRegistry() {
    }

    /** SystemUI's clock container lookup; true when found. See Main.findContainer. */
    interface ContainerLookup {
        boolean find(Feature.Ctx c);
    }

    public static final class Target {
        /** For the log and {@code op features}. */
        public final String name;
        final List<String> packages;
        final List<String> processes;
        /** Gen.markInstallScope after the last feature; SystemUI only (the wallpaper does its own). */
        final boolean marksInstallScope;
        final ContainerLookup container;
        private final Supplier<List<Feature>> features;

        Target(String name, String[] packages, String[] processes,
               boolean marksInstallScope, ContainerLookup container,
               Supplier<List<Feature>> features) {
            this.name = name;
            this.packages = Arrays.asList(packages);
            this.processes = Arrays.asList(processes);
            this.marksInstallScope = marksInstallScope;
            this.container = container;
            this.features = features;
        }

        /** Built when installed, not when the table is: every process loads this class. */
        List<Feature> features() {
            return features.get();
        }

        public Feature.Ctx ctx(ClassLoader cl) {
            return new Feature.Ctx(cl);
        }
    }

    private static String[] packages(String... names) {
        return names;
    }

    private static String[] processes(String... names) {
        return names;
    }

    private static Target single(String name, String[] packages, String[] processes,
                                 Feature feature) {
        return new Target(name, packages, processes, false, null,
                () -> Collections.singletonList(feature));
    }

    static final List<Target> TARGETS = Arrays.asList(
            new Target("SystemUI",
                    packages("com.android.systemui"),
                    processes("com.android.systemui"),
                    true, Main::findContainer, Main::systemUiFeatures),
            single("MiWallpaper",
                    packages("com.miui.miwallpaper"),
                    processes("com.miui.miwallpaper"),
                    Feature.of("feature:wallpaper", false, c -> WallpaperProbe.install(c.host))),
            // The lock screen editor and the always-on display, which judge a wallpaper's depth
            // with their own copies of the same classes and hold the ceiling on saved lock
            // screens.
            single("AOD",
                    packages("com.miui.aod"),
                    processes("com.miui.aod"),
                    Feature.of("feature:aod", false, c -> HyperTweaks.aod(c.host))),
            // The back screen's app. Its own allow-list decides what it draws, and SystemUI is
            // not on it - see RearScreen.
            single("rear screen",
                    packages("com.xiaomi.subscreencenter"),
                    processes("com.xiaomi.subscreencenter"),
                    Feature.of("feature:rear_screen", false, c -> RearScreen.handle(c.host))),
            // The one player we hook. Its lyric never reaches the session on its own, so it is
            // fetched and published from inside the app - see AppleLyrics. Nothing else about
            // the module runs in this process.
            single("Apple Music",
                    packages(AppleLyrics.PKG),
                    processes("com.apple.android.music"),
                    Feature.of("feature:apple_lyrics", false, c -> AppleLyrics.handle(c.host))),
            // 高德, for the navigation map on the lock screen: only watching whether its page is
            // ready to draw one. See AmapImmerse and AmapNavScene.
            // A literal, not AmapImmerse.PKG: that is not a compile-time constant, and reading it
            // here would initialise 高德's objects in every process that loads this table.
            single("高德",
                    packages("com.autonavi.minimap"),
                    processes("com.autonavi.minimap",
                            "com.autonavi.minimap:locationservice",
                            "com.autonavi.minimap:widgetProvider"),
                    Feature.of("feature:amap", false, c -> AmapImmerse.handle(c.host))),
            // 小爱建议, for its subway ride-code card on the island. See MetroCodeIsland.
            single("小爱建议",
                    packages(MetroCodeIsland.PKG),
                    processes("com.miui.personalassistant"),
                    Feature.of("feature:metro_code", false,
                            c -> MetroCodeIsland.INSTANCE.install(c.host))));

    public static Target forPackage(String pkg) {
        for (Target t : TARGETS) {
            if (t.packages.contains(pkg)) return t;
        }
        return null;
    }

    public static Target forProcess(String proc) {
        for (Target t : TARGETS) {
            if (t.processes.contains(proc)) return t;
        }
        return null;
    }
}
