package com.os4.musiccover.updater

import android.content.Context
import android.os.SystemClock

/**
 * The answer to "is there a newer release", held for the life of the process.
 *
 * It lives outside the composition on purpose. The check runs once when the app starts, and the
 * About page is a visitor to that answer rather than the reason for it - so leaving the tab and
 * coming back, or the Activity being recreated, must not go to the network again.
 *
 * The floor between checks is not decoration either: `api.github.com` allows sixty unauthenticated
 * requests an hour per address, and without it a page that re-checks on every visit would spend
 * that budget by itself.
 */
object UpdateCheck {

    private const val MIN_INTERVAL_MS = 10L * 60 * 1000

    @Volatile
    var result: UpdateInfo? = null
        private set

    @Volatile
    private var checkedAt: Long = 0L

    /** False when nothing has been asked yet, or when the last answer is old enough to re-ask. */
    fun isFresh(): Boolean {
        val at = checkedAt
        return at != 0L && SystemClock.elapsedRealtime() - at in 0 until MIN_INTERVAL_MS
    }

    fun store(info: UpdateInfo?) {
        result = info
        checkedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Asks GitHub, unless the last answer is still fresh - or unless this build may not update
     * itself at all, in which case nobody should be told about a release they cannot install.
     *
     * Suspends rather than blocking; safe to call from the main dispatcher.
     */
    suspend fun refresh(context: Context) {
        if (!UpdateApi.enabled(context)) return
        if (isFresh()) return
        store(UpdateApi.latest())
    }
}
