// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package com.os4.musiccover

/**
 * The editor and the full-screen charging animation reuse the keyguard window, and the control
 * centre is drawn over it. Their explicit SystemUI states decide whether the lock screen the mini
 * player belongs to is actually what is showing.
 */
internal object MiniPlayerScene {
    @Volatile private var editorActive = false
    @Volatile private var chargingActive = false
    @Volatile private var controlCenterActive = false
    @Volatile var keyguardGoingAway = false
        private set
    @Volatile var aodActive = false
        private set
    @Volatile private var fullScreenAod = false

    /** The ordinary/custom AOD owns the screen; only the full-screen AOD keeps this row. */
    val customAodActive: Boolean
        get() = aodActive && !fullScreenAod

    val fullScreenAodActive: Boolean
        get() = aodActive && fullScreenAod

    val hasBlockingOverlay: Boolean
        get() = editorActive || chargingActive || controlCenterActive

    val blocksMiniPlayer: Boolean
        get() = editorActive || chargingActive

    val controlCenterIsActive: Boolean
        get() = controlCenterActive

    /** Each hook alone: a renamed class costs the one signal it carried, nothing else. */
    fun install(cl: ClassLoader) {
        fun hook(className: String, methodName: String, before: (List<Any?>) -> Unit) {
            runCatching {
                Xp.hookAll(Xp.findClass(className, cl), methodName) { chain ->
                    before(chain.args)
                    chain.proceed()
                }
            }.onFailure { Xp.log("MCMini: scene hook $methodName unavailable: $it") }
        }
        hook("com.android.keyguard.injector.KeyguardViewMediatorInjector", "keyguardGoingAway") {
            setKeyguardGoingAway(true)
        }
        hook("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl", "notifyKeyguardState") {
            if (it.firstOrNull() == true) setKeyguardGoingAway(false)
        }
        hook("com.android.systemui.statusbar.StatusBarStateControllerImpl", "setIsDozing") {
            setAodActive(it.firstOrNull() == true)
        }
        hook("com.android.keyguard.editor.KeyguardEditorHelper", "setEditorState") {
            setEditorActive(it.firstOrNull()?.toString() != "IDEL")
        }
        hook("com.miui.charge.container.MiuiChargeAnimationView", "addChargeView") {
            setChargingActive(true)
        }
        hook("com.miui.charge.container.MiuiChargeAnimationView", "removeChargeView") {
            setChargingActive(false)
        }
        runCatching {
            val listener = Xp.findClass(
                "com.miui.systemui.controlcenter.container.ControlCenterContainerController\$onExpandChangeListener\$1",
                cl,
            )
            Xp.hookAll(listener, "onExpandStateChanged") { chain ->
                val state = chain.args.firstOrNull()?.toString()
                if (state != null && state != "COLLAPSED") setControlCenterActive(true)
                val result = chain.proceed()
                if (state == "COLLAPSED") setControlCenterActive(false)
                result
            }
        }.onFailure { Xp.log("MCMini: control centre scene hook unavailable: $it") }
    }

    private fun setEditorActive(active: Boolean) {
        if (editorActive == active) return
        editorActive = active
        MiniPlayerRuntime.refresh()
    }

    private fun setChargingActive(active: Boolean) {
        if (chargingActive == active) return
        chargingActive = active
        MiniPlayerRuntime.refresh()
    }

    private fun setControlCenterActive(active: Boolean) {
        if (controlCenterActive == active) return
        controlCenterActive = active
        MiniPlayerRuntime.refresh()
    }

    private fun setAodActive(active: Boolean) {
        val keyguardExitReset = active && keyguardGoingAway
        val fullScreen = active && Main.fullAodOn()
        if (keyguardExitReset) keyguardGoingAway = false
        if (aodActive == active && fullScreenAod == fullScreen && !keyguardExitReset) return
        fullScreenAod = fullScreen
        aodActive = active
        Xp.log("MCMini: AOD active=$active fullScreen=$fullScreen")
        if (!active) MiniPlayerRuntime.aodEnded()
        MiniPlayerRuntime.refresh()
    }

    private fun setKeyguardGoingAway(goingAway: Boolean) {
        if (keyguardGoingAway == goingAway) return
        keyguardGoingAway = goingAway
        if (goingAway) MiniPlayerRuntime.forgetRestoreScene()
        MiniPlayerRuntime.refresh()
    }
}
