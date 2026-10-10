/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
package com.os4.musiccover.ui.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.R
import com.os4.musiccover.ui.component.effect.BgEffectBackground
import com.os4.musiccover.ui.util.BlurredBar
import com.os4.musiccover.ui.util.ColorBlendToken
import com.os4.musiccover.ui.util.isInDarkTheme
import com.os4.musiccover.ui.util.openQqGroup
import com.os4.musiccover.ui.util.openTelegramGroup
import com.os4.musiccover.ui.util.pageContentPadding
import com.os4.musiccover.ui.util.pageScrollModifiers
import com.os4.musiccover.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import kotlin.math.abs
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun AboutPageContent(
    openLicensePage: () -> Unit,
    openCreditsPage: () -> Unit,
    isBlurEnabled: Boolean = true,
    isCurrent: () -> Boolean = { true },
) {
    // Owns the check, the install and the four dialogs; see UpdateUi.kt. It has to sit above the
    // Scaffold because the dialogs open their own windows and cannot be nested in the page body.
    val update = rememberUpdateController(isCurrent)
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val blurActive by remember(backdrop, isBlurEnabled) { derivedStateOf { isBlurEnabled && backdrop != null && scrollProgress == 1f } }

    Scaffold(
        popupHost = { },
        topBar = {
            val barColor = if (blurActive) {
                Color.Transparent
            } else {
                if (collapsed) colorScheme.surface else Color.Transparent
            }
            val titleColor = colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            BlurredBar(backdrop, blurActive) {
                SmallTopAppBar(
                    title = stringResource(R.string.about),
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    defaultWindowInsetsPadding = false,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (isBlurEnabled && backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            AboutContent(
                padding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 0.dp,
                ),
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                lazyListState = lazyListState,
                scrollProgressProvider = { scrollProgress },
                openLicensePage = openLicensePage,
                openCreditsPage = openCreditsPage,
                isBlurEnabled = isBlurEnabled,
                isCurrent = isCurrent,
                update = update,
            )
        }
    }

    UpdateDialogs(update)
}

@Composable
private fun AboutContent(
    padding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    openLicensePage: () -> Unit,
    openCreditsPage: () -> Unit,
    isBlurEnabled: Boolean,
    isCurrent: () -> Boolean,
    update: UpdateController,
) {
    val uriHandler = LocalUriHandler.current
    // `rememberBlurBackdrop` answers "can this device blur", not "did the user ask for it": on
    // Android 13 and up it hands back a backdrop whatever the setting says. The gate has to be
    // here. Everything below reads `contentBackdrop != null`, so with the setting off the layer
    // was still recorded and the four textureBlur passes still ran - the page kept paying for a
    // feature whose switch was off, and the switch was a lie.
    val contentBackdrop = rememberBlurBackdrop()?.takeIf { isBlurEnabled }
    var blurRadius by remember { mutableFloatStateOf(60f) }
    var noiseCoefficient by remember { mutableFloatStateOf(BlurDefaults.NoiseCoefficient) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }

    val scrollPadding = pageContentPadding(
        padding,
        padding,
        false,
        extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
    )
    val logoPadding = pageContentPadding(
        padding,
        padding,
        false,
        extraTop = 40.dp,
        extraStart = WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues().calculateRightPadding(LayoutDirection.Ltr),
    )

    val isInDark = isInDarkTheme()

    val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    val appName = stringResource(R.string.app_name)
    val ctx = LocalContext.current
    // BuildConfig rather than the installed package's metadata: it is the same string for this
    // build, and it is not a call into the package manager on a composition that the pager can
    // trigger at any time.
    val versionName = BuildConfig.VERSION_NAME

    BgEffectBackground(
        // Only while this page is the one on screen. The pager keeps it composed for a whole tab
        // away in either direction, and the background is a full-screen runtime shader whose
        // animation loop invalidates draw every frame - so "composed" was costing a shader
        // evaluation per frame, per enclosing layer, for the entire length of every trip between
        // the first tab and this one. Off screen there is nothing to animate for.
        dynamicBackground = isCurrent(),
        isFullSize = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {

        // Scrollable content
        LazyColumn(
            overscrollEffect = null,
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                ),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateLeftPadding(LayoutDirection.Ltr),
                end = scrollPadding.calculateRightPadding(LayoutDirection.Ltr),
            ),
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        ),
                )
            }

            item(key = "about") {
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        UpdateRows(
                            update = update.update,
                            installing = update.installing,
                            onGetUpdate = update.showLinks,
                            onDirectUpdate = { update.directUpdate(ctx) },
                        )
                    }
                    // Credits has a card of its own, right under the update rows: it is the one
                    // page here that names people instead of licences, and inside the licence card
                    // it read as one more licence.
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.about_credits),
                            onClick = openCreditsPage,
                        )
                    }
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.about_source_code),
                            summary = stringResource(R.string.about_source_code_summary),
                            onClick = { uriHandler.openUri("https://github.com/zyl6932/HyperMusicCover") },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_telegram),
                            summary = stringResource(R.string.about_telegram_summary),
                            onClick = {
                                // Falls back to the browser only when no Telegram client answered.
                                if (!ctx.openTelegramGroup("https://t.me/HyperMusicCover")) {
                                    uriHandler.openUri("https://t.me/HyperMusicCover")
                                }
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_qq_group),
                            summary = stringResource(R.string.about_qq_group_summary),
                            onClick = {
                                // The group number, not the qm.qq.com link, is what QQ's card
                                // route takes; the link is only here for the browser fallback.
                                if (!ctx.openQqGroup("392493127")) {
                                    uriHandler.openUri("https://qm.qq.com/q/RcLbYXgBy2")
                                }
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_feedback),
                            summary = stringResource(R.string.about_feedback_summary),
                            onClick = { uriHandler.openUri("https://github.com/zyl6932/HyperMusicCover/issues") },
                        )
                    }
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.license_agpl),
                            onClick = { uriHandler.openUri("https://www.gnu.org/licenses/agpl-3.0.txt") },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_dependencies),
                            onClick = openLicensePage,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // Logo area — floating overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = logoPadding.calculateRightPadding(LayoutDirection.Ltr),
                )
                .onSizeChanged { size ->
                    // The spacer in the list reserves this height, so every write is a re-layout
                    // of the list. Compose already drops a write of the same value; this also
                    // drops one that differs by less than a pixel, which is the only way it can
                    // differ without the logo having actually changed size.
                    val measured = with(density) { size.height.toDp() }
                    if (abs(measured.value - logoHeightDp.value) >= 1f) logoHeightDp = measured
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        clip = true
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .squircleClip(cornerRadius = 28.dp),
            ) {
                Image(
                    modifier = Modifier.size(100.dp),
                    painter = painterResource(R.drawable.ic_about_logo),
                    contentDescription = null,
                )
            }
            MiuixText(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (contentBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = noiseCoefficient,
                                colors = BlurDefaults.blurColors(
                                    blendColors = logoBlend,
                                ),
                                contentBlendMode = ComposeBlendMode.DstIn,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = appName,
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MiuixText(
                    // The version is tappable for the same reason the "update available" line
                    // below it is: the changelog is the one thing a person wants after seeing a
                    // version number, and here it is one tap away. Indication stays off so the
                    // press does not paint a band of shadow across the whole centred text.
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { update.showVersionNotes() },
                        )
                        .padding(vertical = 4.dp),
                    color = colorScheme.onSurfaceVariantSummary,
                    text = versionName,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                UpdateHint(
                    modifier = Modifier.fillMaxWidth(),
                    update = update.update,
                    onShowNotes = update.showNotes,
                )
            }
        }
    }
}
