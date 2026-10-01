package com.os4.musiccover;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;

/**
 * Who may drive the module's probe receivers.
 *
 * Those receivers live in other apps' processes - SystemUI, the wallpaper process, 高德 - and
 * have to be exported: the app, the other hooked processes and adb all reach them from outside.
 * Exported with nothing in front of them, any app on the phone could do the same: read what is
 * playing out of `query`, wipe the user's lock wallpaper with `lockwp --ez clear true`, put a
 * picture of its choosing on the lock screen, or rewrite every setting the module keeps.
 *
 * So each receiver is registered twice, and every broadcast is let through by exactly one of them:
 *
 *   - Without a permission, for the module's own senders. Every send in this module shares its
 *     identity ({@link #options()}), so the system tells the receiver which package sent it, and
 *     only the packages named at registration - the app and the processes the module runs in - and
 *     the core uids (root, system, shell) are answered.
 *   - Behind android.permission.DUMP, for adb. `am broadcast` cannot share an identity, but the
 *     shell holds DUMP and no third-party app can. Only an anonymous broadcast is answered here;
 *     one that carries an identity is the other registration's, so nothing is handled twice.
 */
final class ProbeGuard {

    private ProbeGuard() {
    }

    private static final String DUMP = "android.permission.DUMP";
    private static final int SHELL_UID = 2000;

    /** The options every probe broadcast goes out with: the sender's identity, for the check. */
    static Bundle options() {
        BroadcastOptions o = BroadcastOptions.makeBasic();
        o.setShareIdentityEnabled(true);
        return o.toBundle();
    }

    /** A probe broadcast, sent the way the receivers will take it. */
    static void send(Context ctx, Intent intent) {
        ctx.sendBroadcast(intent, null, options());
    }

    /** A receiver that knows which of its two registrations it came in through, and whom it trusts. */
    abstract static class Receiver extends BroadcastReceiver {
        private boolean viaDump;
        private String[] trusted = new String[0];
        private String tag = "";
    }

    /**
     * Registers two receivers from [make] for [filter]. [trusted] are the packages whose
     * broadcasts are answered; [tag] prefixes the log line for a broadcast that is not.
     */
    static void register(Context ctx, IntentFilter filter, String tag,
                         java.util.function.Supplier<? extends Receiver> make, String... trusted) {
        Receiver open = make.get();
        open.trusted = trusted;
        open.tag = tag;
        ctx.registerReceiver(open, filter, Context.RECEIVER_EXPORTED);
        Receiver adb = make.get();
        adb.viaDump = true;
        adb.trusted = trusted;
        adb.tag = tag;
        ctx.registerReceiver(adb, new IntentFilter(filter), DUMP, null, Context.RECEIVER_EXPORTED);
    }

    /** How often a refused sender is logged: a hostile app could otherwise flood the log. */
    private static final long REFUSED_LOG_MS = 10_000L;
    private static volatile long sRefusedLoggedAt;

    /**
     * Whether [r] should act on the broadcast it is handling now. Called first thing in onReceive;
     * a refused broadcast is left exactly as it arrived, so an ordered one comes back unanswered.
     */
    static boolean admit(Receiver r) {
        int uid = r.getSentFromUid();
        if (r.viaDump) return uid == Process.INVALID_UID;
        if (uid == Process.INVALID_UID) {
            // Anonymous: adb's, answered by the DUMP registration if the sender holds it.
            return false;
        }
        // root, system, shell, and this process's own app.
        if (uid == 0 || uid == Process.SYSTEM_UID || uid == SHELL_UID || uid == Process.myUid()) {
            return true;
        }
        String pkg = r.getSentFromPackage();
        if (pkg != null) {
            for (String t : r.trusted) {
                if (pkg.equals(t)) return true;
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sRefusedLoggedAt > REFUSED_LOG_MS) {
            sRefusedLoggedAt = now;
            Xp.log(r.tag + "refused a probe broadcast from " + pkg + " (uid "
                    + uid + ")");
        }
        return false;
    }
}
