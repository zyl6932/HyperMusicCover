package com.os4.musiccover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The app's only channel to the hook, which lives inside SystemUI's process.
 *
 * There is no shared storage between the two - the module writes its state into SystemUI's own
 * files dir - so the app does not try to read settings from disk. It asks. The same broadcast
 * protocol the adb probe uses carries the commands, and a query goes out as an *ordered*
 * broadcast so the module can answer in the result extras.
 *
 * A reply arriving at all is also how the app knows the module is loaded: no reply within the
 * timeout means LSPosed has not injected it (or SystemUI has not been restarted since).
 */
object ModuleBridge {

    private const val ACTION = "com.os4.musiccover.PROBE"
    private const val TARGET = "com.android.systemui"
    private const val QUERY_TIMEOUT_MS = 1500L

    data class State(
        val alive: Boolean = false,
        val cover: Boolean = false,
        val auto: Boolean = false,
        val bias: Float = 0.34f,
        val clockScale: Float = 0.335f,
        val glassEnd: Float = 0.75f,
        val cardShowing: Boolean = false,
        val lockWallpaperOk: Boolean = false,
        val track: String = "",
        val player: String = "",
    ) {
        /** "Artist - Title" out of the module's packageName|song|artist key. */
        val trackLabel: String
            get() {
                val parts = track.split("|")
                if (parts.size < 3) return ""
                val song = parts[1].takeIf { it.isNotBlank() && it != "null" } ?: return ""
                val artist = parts[2].takeIf { it.isNotBlank() && it != "null" }
                return if (artist == null) song else "$song — $artist"
            }
    }

    private fun intent(op: String): Intent = Intent(ACTION).apply {
        setPackage(TARGET)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        putExtra("op", op)
    }

    fun send(context: Context, op: String, extras: Intent.() -> Unit = {}) {
        context.applicationContext.sendBroadcast(intent(op).apply(extras))
    }

    fun setAuto(context: Context, on: Boolean) = send(context, "auto") { putExtra("on", on) }

    fun setCover(context: Context, on: Boolean) = send(context, "pushart") { putExtra("on", on) }

    fun setBias(context: Context, v: Float) = send(context, "bias") { putExtra("v", v) }

    fun setClockScale(context: Context, v: Float) = send(context, "clockscale") { putExtra("v", v) }

    fun setGlassEnd(context: Context, v: Float) = send(context, "glassend") { putExtra("v", v) }

    /**
     * Asks the module for everything at once. Returns a dead State rather than throwing when the
     * module is not there - "not installed" is a normal thing for this screen to display.
     */
    suspend fun query(context: Context): State = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        var done = false
        val handler = Handler(Looper.getMainLooper())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                cont.resume(fromBundle(getResultExtras(false)))
            }
        }
        // No reply means no module; do not leave the caller hanging on it.
        handler.postDelayed({
            if (!done) {
                done = true
                cont.resume(State())
            }
        }, QUERY_TIMEOUT_MS)

        try {
            app.sendOrderedBroadcast(
                intent("query"), null, receiver, handler, 0, null, null
            )
        } catch (_: Throwable) {
            if (!done) {
                done = true
                cont.resume(State())
            }
        }
    }

    private fun fromBundle(b: Bundle?): State {
        if (b == null || !b.getBoolean("alive", false)) return State()
        return State(
            alive = true,
            cover = b.getBoolean("cover", false),
            auto = b.getBoolean("auto", false),
            bias = b.getFloat("bias", 0.34f),
            clockScale = b.getFloat("clock", 0.335f),
            glassEnd = b.getFloat("glass", 0.75f),
            cardShowing = b.getBoolean("card", false),
            lockWallpaperOk = b.getBoolean("lockwp", false),
            track = b.getString("track") ?: "",
            player = b.getString("player") ?: "",
        )
    }

    /** Restarting SystemUI is how most module changes are picked up. Needs root. */
    fun restartSystemUi(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "kill \$(pidof com.android.systemui)"))
        p.waitFor() == 0
    } catch (_: Throwable) {
        false
    }
}
