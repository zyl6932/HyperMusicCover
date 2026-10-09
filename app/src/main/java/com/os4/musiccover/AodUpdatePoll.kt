package com.os4.musiccover

/** Coalesce content callbacks without requesting frames while AOD content is unchanged. */
internal class AodUpdatePoll {
    companion object { const val INTERVAL_MS = 30_000L }

    var paused = false
        private set
    private var dirty = false
    private var nextPollAt = 0L

    fun setPaused(value: Boolean, now: Long): Boolean {
        if (paused == value) return false
        paused = value
        dirty = false
        nextPollAt = now + INTERVAL_MS
        return true
    }

    fun deferUpdate(): Boolean {
        if (!paused) return false
        dirty = true
        return true
    }

    fun poll(now: Long): Boolean {
        if (!paused || now < nextPollAt) return false
        nextPollAt = now + INTERVAL_MS
        return dirty.also { dirty = false }
    }
}
