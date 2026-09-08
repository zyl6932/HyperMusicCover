/*
 * Adapted from KernelSU (https://github.com/tiann/KernelSU),
 * licensed under the Apache License, Version 2.0.
 */
package com.os4.musiccover.ui.component

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.sqrt

/**
 * Spring spec shared by pager tab navigation.
 *
 * A duration-based tween has to be told how long to take, and the obvious rule - longer for
 * further - makes a two-tab jump feel slack and a three-tab jump feel slower still. A spring has
 * no duration: the distance sets the speed on its own.
 */
internal val PagerNavigationSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * sqrt(322.2f)),
    visibilityThreshold = 0.5f,
)
