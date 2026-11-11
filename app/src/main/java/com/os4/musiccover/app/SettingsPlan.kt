package com.os4.musiccover.app

import com.os4.musiccover.SettingSpec

/**
 * The settings the app keeps in its own preferences (architecture plan, stage 5.3), and the keys
 * one of them is read back under.
 *
 * The module's half of the table is Settings.persisted(): thirty entries and thirty-one keys,
 * because the lyric style has two. The list is repeated here rather than read from there, because
 * this process must not load Settings - it reaches Main, and Main extends XposedModule, which the
 * app has no libxposed to resolve. SettingsPlanTest holds the two lists equal.
 */
internal object SettingsPlan {

    /**
     * One entry per key, in the module's table order. The lyric style's two sit next to each
     * other; every other setting is a single key.
     */
    val specs: List<SettingSpec> = listOf(
        SettingSpec.COVER_FEATURE,
        SettingSpec.BIAS,
        SettingSpec.COVER_STYLE,
        SettingSpec.CLOCK_HEIGHT,
        SettingSpec.CLOCK_SIZE,
        SettingSpec.AOD_SMALL,
        SettingSpec.COLON,
        SettingSpec.MC_TITLE_TAP,
        SettingSpec.MC_PAGE_BUTTON,
        SettingSpec.SEEK_GLOW,
        SettingSpec.TAP_TOGGLE,
        SettingSpec.FADE_WALLPAPER,
        SettingSpec.MOTION,
        SettingSpec.MOTION_ON_DATA,
        SettingSpec.VIDEO_FADE,
        SettingSpec.FADE_MODE,
        SettingSpec.HIDE_FP,
        SettingSpec.FP_AVOID,
        SettingSpec.LYRICS,
        SettingSpec.LYRIC_KEEP_ON,
        SettingSpec.LYRIC_HDR,
        SettingSpec.LYRIC_TRANS,
        SettingSpec.LYRIC_ROMA,
        SettingSpec.LYRIC_ONLINE,
        SettingSpec.LYRIC_ALIGN,
        SettingSpec.LYRIC_SIZE,
        SettingSpec.LYRIC_BG_SIZE,
        SettingSpec.LYRIC_APPEARANCE,
        SettingSpec.TRANSIT,
        SettingSpec.PICKUP,
        SettingSpec.PICKUP_CARD,
    )

    /** The other half of the lyric style's entry. */
    fun isLyricStyleKey(key: String): Boolean =
        key == SettingSpec.LYRIC_SIZE.key || key == SettingSpec.LYRIC_BG_SIZE.key
}

/**
 * What a module answered about its own settings, for the migration (stage 5.4): whether it reads
 * the group at all ([capable], false for a module older than 5.2), the whole value of every key
 * the state file keeps, and the write counter it last applied.
 *
 * [values] can be missing the clock size: that setting is only in the file when someone set it,
 * and when it is absent the module answers the size the clock is actually at - writing that back
 * would turn "follow the height" into a fixed fraction. The migration leaves it out, and the key
 * appearing in preferences later is what tells the module to start following it.
 */
internal class SettingsSnapshot(
    val capable: Boolean,
    val rev: Long,
    val values: Map<String, Any>,
    /** The installation the host's own files belong to; empty from a host that has no id yet. */
    val hostInstallId: String,
)

/**
 * What one page's changes look like before they are committed (stage 5.3): the newest value per
 * key, and the counter the group carries.
 *
 * One key, one value - a slider dragging at sixty frames a second leaves one entry, not sixty.
 * The counter is seeded from the local copy at start and only ever goes up, across the app's
 * restarts and across a group LSPosed may have deleted: the module reports what it applied, and
 * a counter lower than this one is what makes the whole set go back out.
 */
internal class SettingsOutbox<T> {

    private val pending = LinkedHashMap<String, T>()

    var rev: Long = 0L
        private set

    /**
     * Whether the local copy holds a value the group has not been given yet - what a batch
     * written while the module could not take it leaves behind. Kept as a generation rather
     * than a flag so a write during a batch's own round trip is not cleaned away by that batch
     * coming back: the reconcile asks whether anything is newer than what it delivered.
     */
    @Volatile private var marked = 0L
    @Volatile private var cleaned = 0L

    val dirty: Boolean get() = marked > cleaned

    fun markDirty() {
        marked++
    }

    /** The point in the write sequence a delivery is about to cover. */
    fun mark(): Long = marked

    /** Cleans up to [through] only; anything written after it stays dirty. */
    fun clean(through: Long) {
        if (through > cleaned) cleaned = through
    }

    fun seedRev(value: Long) {
        rev = value
    }

    /** Puts a key's newest value; false when the same one was already waiting. */
    fun put(key: String, value: T): Boolean {
        if (pending[key] == value) return false
        pending[key] = value
        return true
    }

    /** Takes everything waiting, for one commit; the outbox is empty afterwards. */
    fun drain(): Map<String, T> {
        val out = LinkedHashMap(pending)
        pending.clear()
        return out
    }

    fun isEmpty(): Boolean = pending.isEmpty()

    fun nextRev(): Long = ++rev
}
