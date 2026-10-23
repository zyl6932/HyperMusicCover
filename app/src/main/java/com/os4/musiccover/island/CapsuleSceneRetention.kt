package com.os4.musiccover.island

/** An explicit navigation-page choice survives opening and folding its notification panel. */
internal class CapsuleSceneRetention {
    private var key: String? = null

    fun opened(next: String, navigation: Boolean, fromBody: Boolean) {
        key = next.takeIf { navigation && fromBody }
    }

    fun keeps(page: String): Boolean = key == page

    fun closed(page: String?) {
        if (page == key) key = null
    }
}
