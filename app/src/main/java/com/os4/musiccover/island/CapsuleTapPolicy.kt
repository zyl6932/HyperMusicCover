package com.os4.musiccover.island

/** A matched action owns the tap, even when its content or entry point is not ready. */
internal object CapsuleTapPolicy {
    enum class Action { MUSIC, NAVIGATION, CLOCK, SCENE, NOTIFICATION }

    fun resolve(music: Boolean, redacted: Boolean, navigation: Boolean,
                clock: Boolean, scene: Boolean): Action = when {
        music -> Action.MUSIC
        redacted -> Action.NOTIFICATION
        navigation -> Action.NAVIGATION
        clock -> Action.CLOCK
        scene -> Action.SCENE
        else -> Action.NOTIFICATION
    }
}

/** Both the full pill and the small disc acknowledge the same resolved action. */
internal interface CapsuleFeedback {
    fun feedbackBindingVersion(): Long
    fun clearLyricFeedback()
    fun showLyricStatus(enabled: Boolean)
    fun showLyricPending()
    fun showLyricUnavailable()
    fun showStatus(text: String)
}
