package com.os4.musiccover

import java.util.WeakHashMap

/** Hold time-derived content in AOD; a new state token can still supply a new value. */
internal class AodContentSnapshot<K : Any, V : Any> {
    var paused = false
        private set
    private val held = WeakHashMap<K, V>()

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        held.clear()
    }

    fun value(key: K, read: () -> V): V = if (paused) held.getOrPut(key, read) else read()
}
