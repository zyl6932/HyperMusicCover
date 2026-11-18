/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
package com.os4.musiccover.ui.screen.about

import com.os4.musiccover.ui.util.nextCard
import com.os4.musiccover.ui.util.AppCard
import com.os4.musiccover.ui.util.firstCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.BlurredBar
import com.os4.musiccover.ui.util.GlassPageBar
import com.os4.musiccover.ui.util.LocalOs4Ui
import com.os4.musiccover.ui.util.LocalPageBackdrop
import com.os4.musiccover.ui.util.PageBackButton
import com.os4.musiccover.ui.util.pageScrollModifiers
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

data class LibraryInfo(
    val name: String,
    val artifactVersion: String,
    val website: String,
)

@Composable
fun LicensePageContent(
    onBack: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val os4 = LocalOs4Ui.current
    val blurActive = (isBlurEnabled || os4) && backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            if (os4) {
                GlassPageBar(
                    title = stringResource(R.string.third_party_licenses_title),
                    backdrop = backdrop,
                    scrollBehavior = topAppBarScrollBehavior,
                    onBack = onBack,
                )
            } else {
                BlurredBar(backdrop, blurActive, topAppBarScrollBehavior) {
                    TopAppBar(
                        title = stringResource(R.string.third_party_licenses_title),
                        color = barColor,
                        scrollBehavior = topAppBarScrollBehavior,
                        // Shared with every other page that has a back arrow. This page keeps its
                        // own Scaffold rather than using PageScaffold: its list is inset by the
                        // display cutout on top of the window insets the frame already applies,
                        // which no other page does.
                        navigationIcon = { PageBackButton(onBack) },
                    )
                }
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val uriHandler = LocalUriHandler.current
        val lazyListState = rememberLazyListState()
        val layoutDirection = LocalLayoutDirection.current
        val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding(),
            start = innerPadding.calculateStartPadding(layoutDirection) + cutoutPadding.calculateLeftPadding(LayoutDirection.Ltr),
            end = innerPadding.calculateEndPadding(layoutDirection) + cutoutPadding.calculateRightPadding(LayoutDirection.Ltr),
        )

        val miuixName = stringResource(R.string.tl_miuix)
        val miuixSummary = stringResource(R.string.tl_miuix_summary)
        val kotlinName = stringResource(R.string.tl_kotlin)
        val kotlinSummary = stringResource(R.string.tl_kotlin_summary)
        val composeName = stringResource(R.string.tl_compose)
        val composeSummary = stringResource(R.string.tl_compose_summary)
        val androidxName = stringResource(R.string.tl_ksp)
        val androidxSummary = stringResource(R.string.tl_ksp_summary)
        val coroutinesName = stringResource(R.string.tl_coroutines)
        val coroutinesSummary = stringResource(R.string.tl_coroutines_summary)
        val materialIconsName = stringResource(R.string.tl_material_icons)
        val materialIconsSummary = stringResource(R.string.tl_material_icons_summary)

        val libraries = remember {
            listOf(
                LibraryInfo(miuixName, miuixSummary, "https://github.com/compose-miuix-ui/miuix"),
                LibraryInfo(kotlinName, kotlinSummary, "https://kotlinlang.org/"),
                LibraryInfo(composeName, composeSummary, "https://developer.android.com/jetpack/compose"),
                LibraryInfo(androidxName, androidxSummary, "https://developer.android.com/jetpack/androidx"),
                LibraryInfo(coroutinesName, coroutinesSummary, "https://github.com/Kotlin/kotlinx.coroutines"),
                LibraryInfo(materialIconsName, materialIconsSummary, "https://developer.android.com/jetpack/androidx/compose/material-icons"),
            )
        }

        CompositionLocalProvider(LocalPageBackdrop provides backdrop.takeIf { blurActive }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier)
            ) {
                LazyColumn(
                    overscrollEffect = null,
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = topAppBarScrollBehavior,
                        )
                        .padding(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                        ),
                    contentPadding = PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                    // One card of rows, as the credits page and miuix's example list theirs: a card
                    // per library was a column of single-row islands (2026-10-14).
                    item(key = "libraries") {
                        AppCard(modifier = Modifier.firstCard()) {
                            libraries.forEach { library ->
                                ArrowPreference(
                                    title = library.name,
                                    summary = "${library.artifactVersion} · ${library.website}",
                                    onClick = {
                                        uriHandler.openUri(library.website)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
