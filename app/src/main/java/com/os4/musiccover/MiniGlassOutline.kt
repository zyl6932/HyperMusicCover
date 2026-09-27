// SPDX-License-Identifier: Apache-2.0
package com.os4.musiccover

import android.view.View
import java.util.WeakHashMap

/** Keeps Xiaomi's native glass outline and SDF at the material view's actual size. */
internal object MiniGlassOutline {
    private const val GLASS_OUTLINE = 8192
    private val sizes = WeakHashMap<View, Pair<Int, Int>>()
    private val enhance by lazy {
        runCatching { View::class.java.getMethod("setMiBackgroundBlurEnhanceFlag",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }.getOrNull()
    }
    private val sdfSize by lazy {
        runCatching { View::class.java.getMethod("setMiGlassSdfMaxSize",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType) }.getOrNull()
    }
    private var warned = false
    private val loggedHosts = HashSet<String>()

    fun dressed(view: View, glass: Boolean) {
        sizes.remove(view)
        if (glass) {
            sizes[view] = 0 to 0
            geometry(view)
        }
    }

    fun geometry(view: View) {
        val previous = sizes[view] ?: return
        val w = view.width
        val h = view.height
        if (w <= 1 || h <= 1 || previous.first == w && previous.second == h) return
        view.invalidateOutline()
        val result = runCatching {
            requireNotNull(sdfSize) { "setMiGlassSdfMaxSize unavailable" }
                .invoke(view, w.toFloat(), h.toFloat())
            requireNotNull(enhance) { "setMiBackgroundBlurEnhanceFlag unavailable" }
                .invoke(view, GLASS_OUTLINE, GLASS_OUTLINE)
        }
        sizes[view] = w to h
        if (result.isSuccess) {
            val host = view.parent?.javaClass?.simpleName.orEmpty()
            if (loggedHosts.add(host)) Xp.log("MCMini: native glass outline active on $host ${w}x$h")
        } else if (!warned) {
            warned = true
            Xp.log("MCMini: native glass outline unavailable: ${result.exceptionOrNull()}")
        }
    }
}
