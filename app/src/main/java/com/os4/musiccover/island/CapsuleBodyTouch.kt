package com.os4.musiccover.island

/** Only a completed body gesture can click. Button gestures remain with the child view. */
internal class CapsuleBodyTouch {
    var tracking = false
        private set

    fun down() { tracking = true }
    fun cancel() { tracking = false }
    fun move(x: Float, y: Float, width: Int, height: Int) {
        if (!inside(x, y, width, height)) cancel()
    }
    fun up(x: Float, y: Float, width: Int, height: Int): Boolean {
        val click = tracking && inside(x, y, width, height)
        cancel()
        return click
    }
    private fun inside(x: Float, y: Float, width: Int, height: Int) =
        x >= 0f && y >= 0f && x < width && y < height
}
