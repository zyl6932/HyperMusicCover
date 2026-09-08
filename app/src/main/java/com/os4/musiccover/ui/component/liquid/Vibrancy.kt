/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.os4.musiccover.ui.component.liquid

// Adapted from Kyant0/AndroidLiquidGlass — https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** Lightweight stand-in for Kyant's `vibrancy()`. */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
