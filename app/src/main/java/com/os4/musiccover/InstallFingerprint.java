package com.os4.musiccover;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Which installation a host's cached files belong to (stage 5.5).
 *
 * The app's own data does not outlive an uninstall, but a host's does: its files, its shared
 * preferences and whatever it holds in memory are all still there when the app comes back. That
 * is right for a normal upgrade and wrong for a new installation, where the old settings would
 * be handed to the new app by stage 5.4's migration and the old cover would show before the new
 * one arrived.
 *
 * So the app writes a random id into the group, {@code _install}, and every host keeps the one
 * it last acted on in {@code mc_install_id}. A different id is a new installation: this process
 * clears what belongs to the old one and, in SystemUI, puts the settings back to their defaults
 * before recording the new id. An id that is absent on either side changes nothing - that is a
 * first start, or a framework whose group was lost, and neither may throw work away.
 */
final class InstallFingerprint {
    private static final String TAG = "[MCProbe] install: ";
    private static final String FILE = "mc_install_id";

    private InstallFingerprint() {
    }

    /**
     * The group's id, read here rather than through {@link SettingsPrefs}: only SystemUI
     * installs that, and the wallpaper, the AOD, Apple Music and 小爱建议 keep files of their
     * own too. Called from boot in every host, after the state file has been read, so what the
     * reset writes is not overwritten by that read.
     */
    static void check(Context c) {
        if (c == null) return;
        try {
            android.content.SharedPreferences p =
                    Xp.api().getRemotePreferences(SettingSpec.PREFS_GROUP);
            seen(c, p.getString(SettingSpec.PREFS_INSTALL, null));
        } catch (Throwable t) {
            Xp.w(TAG + "reading the group failed: " + t);
        }
    }

    /** The id this process last acted on, or null when it has never seen one. */
    static String recorded(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) return null;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            Xp.w(TAG + "reading the id failed: " + t);
            return null;
        }
    }

    /**
     * Acts on the group's id: records it the first time, and on a different one clears this
     * process's files and resets SystemUI's settings before recording it. Safe to call often -
     * the same id does nothing.
     */
    static void seen(Context c, String groupId) {
        if (c == null || groupId == null || groupId.isEmpty()) return;
        String mine = recorded(c);
        if (mine == null) {
            write(c, groupId);
            Xp.log(TAG + c.getPackageName() + " first sees " + groupId);
            return;
        }
        if (mine.equals(groupId)) return;
        Xp.log(TAG + c.getPackageName() + " new installation " + mine + " -> " + groupId);
        clear(c);
        // Only SystemUI has the whole settings table and the views its effects move. The
        // other hosts keep no settings of their own, only the files cleared above.
        if ("com.android.systemui".equals(c.getPackageName())) {
            try {
                Settings.resetDefaults(c);
            } catch (Throwable t) {
                Xp.w(TAG + "resetting the settings failed: " + t);
            }
        }
        write(c, groupId);
    }

    private static void write(Context c, String id) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(id.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) throw new IllegalStateException("rename failed");
            }
        } catch (Throwable t) {
            Xp.w(TAG + "writing the id failed: " + t);
        }
    }

    /** What this package cached for the installation that is gone. */
    private static void clear(Context c) {
        String pkg = c.getPackageName();
        File files = c.getFilesDir();
        if ("com.android.systemui".equals(pkg)) {
            delete(files,
                    "mc_cover_state", "mc_cover_state.before",
                    "mc_shot_card.png", "mc_shot_sl.png", "mc_shot_sr.png",
                    "mc_shot_clock0.png", "mc_shot_clock1.png", "mc_shot_date.png",
                    "mc_shot_art.jpg",
                    "mc_lyricinfo.json", "mc_local.txt", "mc_lyricraw.txt",
                    "mc_mini.txt", "mc_ncm.txt", "mc_xiaoai_stopped",
                    "mc_pickup_cloud.json", "mc_card_video.mp4",
                    "mc_desk_clock_rcf.ttf", "mc_barwatch.txt");
            clearPrefs(c, "hmc_mini", "hmc_fpquick");
        } else if ("com.miui.miwallpaper".equals(pkg)) {
            delete(files,
                    "mc_video.mp4", "mc_cover.mp4", "mc_cover_blur.mp4",
                    "mc_src.raw", "mc_takeover_broken");
        } else if ("com.miui.aod".equals(pkg)) {
            delete(c.getCacheDir(), "mc_tweaks.txt");
        } else if ("com.apple.android.music".equals(pkg)) {
            delete(files, "mc_apple.txt");
        }
        // 小爱建议's commute memory is what the user taught it, not a cache of ours: it stays
        // (user, 2026-11-11). The back screen, 高德 and the control centre plugin write no
        // files of their own.
    }

    private static void delete(File dir, String... names) {
        for (String name : names) {
            try {
                File f = new File(dir, name);
                if (f.exists() && !f.delete()) Xp.w(TAG + "could not delete " + f);
            } catch (Throwable t) {
                Xp.w(TAG + "deleting " + name + " failed: " + t);
            }
        }
    }

    private static void clearPrefs(Context c, String... names) {
        for (String name : names) {
            try {
                // clear() rather than deleteSharedPreferences(): the module holds these
                // instances, and a new instance next to an old one is two truths.
                c.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            } catch (Throwable t) {
                Xp.w(TAG + "clearing " + name + " failed: " + t);
            }
        }
    }
}
