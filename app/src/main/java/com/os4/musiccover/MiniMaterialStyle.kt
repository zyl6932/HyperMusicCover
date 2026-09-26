// SPDX-License-Identifier: Apache-2.0
// Material parameters adapted from HyperChanger 1.1.3 (Copyright 2026 btm_m).
package com.os4.musiccover

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import org.json.JSONObject

/** One independently saved lockscreen background recipe. All radii use the OEM's pixel units. */
internal data class MiniMaterialStyle(
    val mode: Int = SYSTEM,
    val pureColor: Int = 0x73000000,
    val advancedColor: Int = Color.WHITE,
    val advancedOpacity: Int = 14,
    val advancedBlur: Int = 40,
    val advancedHighlight: Boolean = false,
    val softColor: Int = Color.WHITE,
    val softOpacity: Int = 10,
    val softBackdropBlur: Int = 40,
    val softGlassBlur: Int = 36,
    val softLuminance: Float = 0.14f,
) {
    /** An OEM card change only invalidates the system mode. Hidden-mode parameters do not redraw. */
    fun key(oemGeneration: Int): String = when (mode) {
        PURE -> "pure:$pureColor"
        ADVANCED -> "advanced:$advancedColor:$advancedOpacity:$advancedBlur:$advancedHighlight"
        SOFT_GLASS -> "soft:$softColor:$softOpacity:$softBackdropBlur:$softGlassBlur:$softLuminance"
        else -> "system:$oemGeneration"
    }

    companion object {
        const val SYSTEM = 0
        const val PURE = 1
        const val ADVANCED = 2
        const val SOFT_GLASS = 3

        fun defaults(shortcut: Boolean) = MiniMaterialStyle(
            pureColor = if (shortcut) 0x73FFFFFF else 0x73000000,
        )

        fun fromJson(raw: JSONObject?, shortcut: Boolean = false): MiniMaterialStyle {
            val base = defaults(shortcut)
            if (raw == null) return base
            fun number(key: String, fallback: Int, min: Int, max: Int): Int =
                runCatching { raw.getInt(key).coerceIn(min, max) }.getOrDefault(fallback)
            fun color(key: String, fallback: Int): Int =
                runCatching { raw.getInt(key) }.getOrDefault(fallback)
            val luminance = runCatching { raw.getDouble("softLuminance").toFloat() }
                .getOrDefault(base.softLuminance).takeIf(Float::isFinite)
                ?.coerceIn(0f, 0.4f) ?: base.softLuminance
            return MiniMaterialStyle(
                mode = number("mode", base.mode, SYSTEM, SOFT_GLASS),
                pureColor = color("pureColor", base.pureColor),
                advancedColor = color("advancedColor", base.advancedColor),
                advancedOpacity = number("advancedOpacity", base.advancedOpacity, 0, 100),
                advancedBlur = number("advancedBlur", base.advancedBlur, 0, 40),
                advancedHighlight = runCatching { raw.getBoolean("advancedHighlight") }
                    .getOrDefault(base.advancedHighlight),
                softColor = color("softColor", base.softColor),
                softOpacity = number("softOpacity", base.softOpacity, 0, 100),
                softBackdropBlur = number("softBackdropBlur", base.softBackdropBlur, 0, 40),
                softGlassBlur = number("softGlassBlur", base.softGlassBlur, 0, 40),
                softLuminance = luminance,
            )
        }

        fun normalizedJson(raw: JSONObject?, shortcut: Boolean = false): JSONObject {
            val value = fromJson(raw, shortcut)
            return JSONObject().apply {
                put("mode", value.mode)
                put("pureColor", value.pureColor)
                put("advancedColor", value.advancedColor)
                put("advancedOpacity", value.advancedOpacity)
                put("advancedBlur", value.advancedBlur)
                put("advancedHighlight", value.advancedHighlight)
                put("softColor", value.softColor)
                put("softOpacity", value.softOpacity)
                put("softBackdropBlur", value.softBackdropBlur)
                put("softGlassBlur", value.softGlassBlur)
                put("softLuminance", value.softLuminance.toDouble())
            }
        }
    }
}

/** Applies only the custom modes. The existing recorded-card path owns SYSTEM. */
internal object MiniMaterialRenderer {
    private val loggedFailures = HashSet<Int>()
    private val glassParameters = floatArrayOf(
        0.67f, 0.16f, 0.09f, 0f, 0.24f, 1.4f, -0.02f, 0.3f, 0.6f, 1f,
        0.03f, 1f, 1f, 1f, 0.1f, 0.2f, 0.3f, 1f, 1f, 72f, 3.8f, 80f, 800f,
        1.2f, 1f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4f, 2f,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
    )
    private val bloomParameters = floatArrayOf(
        3f, 180f, 1f, 1f, 1f, 0.05f, 8f, 0.5f, 0.5f, -0.5f, 1f,
        1f, 1f, 0.6f, 0.5f, 0.95f, -0.5f, 1f, 1f, 1f, 0.35f,
    )

    /** The parent previously hosted the recorded OEM material. Turn that compositor state off. */
    fun clearContainer(view: View) {
        val cls = View::class.java
        runCatching { cls.getMethod("clearMiBackgroundBlendColor").invoke(view) }
        runCatching { cls.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType).invoke(view, false) }
        listOf("setMiViewBlurMode", "setMiBackgroundBlurMode", "setMiBackgroundBlurRadius")
            .forEach { name -> runCatching {
                cls.getMethod(name, Int::class.javaPrimitiveType).invoke(view, 0)
            } }
        runCatching {
            cls.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(view, 0, 0)
        }
    }

    fun apply(view: ImageView, style: MiniMaterialStyle, classLoader: ClassLoader) {
        (view.parent as? View)?.let(::clearContainer)
        view.background = null
        view.setImageDrawable(null)
        if (style.mode == MiniMaterialStyle.PURE) {
            fill(view, style.pureColor)
            return
        }
        try {
            fill(view, Color.argb(1, 255, 255, 255))
            when (style.mode) {
                MiniMaterialStyle.ADVANCED -> backdrop(view, style.advancedColor,
                    style.advancedOpacity, style.advancedBlur, style.advancedHighlight)
                MiniMaterialStyle.SOFT_GLASS -> {
                    backdrop(view, style.softColor, style.softOpacity, style.softBackdropBlur, false)
                    glass(view, classLoader, style.softGlassBlur, style.softLuminance)
                }
            }
        } catch (error: Throwable) {
            clearContainer(view)
            fill(view, if (style.mode == MiniMaterialStyle.ADVANCED)
                tint(style.advancedColor, style.advancedOpacity)
                else tint(style.softColor, style.softOpacity))
            if (loggedFailures.add(style.mode)) Xp.log("MCMini: custom material ${style.mode} unavailable: $error")
        }
    }

    private fun fill(view: ImageView, color: Int) {
        view.setImageDrawable(GradientDrawable().apply { setColor(color) })
    }

    private fun tint(rgb: Int, opacity: Int): Int = Color.argb(opacity * 255 / 100,
        Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    private fun backdrop(view: View, color: Int, opacity: Int, radius: Int, highlight: Boolean) {
        val cls = View::class.java
        cls.getMethod("clearMiBackgroundBlendColor").invoke(view)
        cls.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType).invoke(view, true)
        cls.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 1)
        cls.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 1)
        cls.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType).invoke(view, radius)
        cls.getMethod("addMiBackgroundBlendColor", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(view, tint(color, opacity), 101)
        if (highlight) cls.getMethod("setMiBloomStroke", FloatArray::class.java)
            .invoke(view, bloomParameters.copyOf())
    }

    private fun glass(view: View, loader: ClassLoader, radius: Int, luminance: Float) {
        val compat = Class.forName("com.miui.systemui.util.MiGlassCompat", false, loader)
        compat.getMethod("setMiGlassBlurRadius", View::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(null, view, radius, radius * 2)
        compat.getMethod("setMiViewMaterialTypeCompat", Int::class.javaPrimitiveType,
            View::class.java).invoke(null, 1, view)
        compat.getMethod("setMiGlassCompat", View::class.java, FloatArray::class.java)
            .invoke(null, view, glassParameters.copyOf().apply { this[4] = luminance })
    }
}
