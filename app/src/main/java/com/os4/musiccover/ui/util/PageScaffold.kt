package com.os4.musiccover.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The frame every page in this app is built in.
 *
 * It was copy-pasted six times before it was a function, and the copies had drifted: one page
 * put its top inset on the list's own modifier instead of in `contentPadding`, which shortens
 * the list to below the bar - so nothing scrolls under it, the backdrop has nothing to sample up
 * there, and that page's bar stops matching every other page's. That is the failure mode this
 * exists to make impossible rather than merely unlikely.
 *
 * What it fixes, in order: the backdrop and whether the bar is transparent, the Scaffold and its
 * window insets, the `BlurredBar`/`TopAppBar` pair, the single `layerBackdrop` over the content,
 * and the list with `overscrollEffect = null` (which miuix's damped overscroll requires - see
 * [pageScrollModifiers]) and the top inset in the right place.
 *
 * The one thing callers still own is what goes in the list.
 */
@Composable
fun PageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    if (LocalOs4Ui.current) {
        PageScaffoldOs4(
            title = title,
            extraBottomPadding = extraBottomPadding,
            onBack = onBack,
            actions = actions,
            floatingActionButton = floatingActionButton,
            content = content,
        )
    } else {
        PageScaffoldOs3(
            title = title,
            isBlurEnabled = isBlurEnabled,
            extraBottomPadding = extraBottomPadding,
            onBack = onBack,
            actions = actions,
            floatingActionButton = floatingActionButton,
            content = content,
        )
    }
}

/**
 * The OS4 frame: the same list and insets as [PageScaffoldOs3], under a glass top bar.
 *
 * The bar samples the page body through a backdrop rather than blurring a copy of it, and the
 * body is what that backdrop records - never the bar itself: a bar inside its own recording
 * would sample the frame it is drawing, and the blur would feed back on itself. The recording
 * is handed to the page's dropdowns and dialogs through [LocalPageBackdrop], which render
 * outside it.
 */
@Composable
private fun PageScaffoldOs4(
    title: String,
    extraBottomPadding: Dp,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val style = GlassDefaults.style(isInDarkTheme())

    CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        Scaffold(
            // The default popup host, unlike OS3: the glass dropdowns and dialogs are Compose
            // popups and need a Miuix Scaffold to render into. A page inside MainActivity
            // still hosts them at the root scaffold above (renderInRootScaffold), which is
            // outside every recording; this one is the host for the screens of their own.
            floatingActionButton = floatingActionButton,
            topBar = {
                GlassTopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    backdrop = backdrop,
                    progressiveBlur = backdrop != null,
                    style = style,
                    navigationIcon = { if (onBack != null) PageBackButtonOs4(onBack) },
                    actions = { actions() },
                )
            },
            contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Directly after fillMaxSize and before anything that pads the content:
                    // the recording layer's bounds are the sample's bounds, and narrowing them
                    // shifts every blur that reads them.
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            ) {
                PageList(
                    scrollBehavior = scrollBehavior,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + extraBottomPadding,
                    ),
                    content = content,
                )
            }
        }
    }
}

/** The OS3 frame, kept exactly as it was: the switch has to be able to take the user back. */
@Composable
private fun PageScaffoldOs3(
    title: String,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = isBlurEnabled && backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        popupHost = { },
        // The page's main action, where miuix's own docs put it (FloatingActionButton, bound to
        // the Scaffold). Empty on every page that has none.
        floatingActionButton = floatingActionButton,
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { if (onBack != null) PageBackButton(onBack) },
                    actions = { actions() },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier) {
            val bottom = innerPadding.calculateBottomPadding() + extraBottomPadding
            PageList(
                scrollBehavior = scrollBehavior,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottom,
                ),
                content = content,
            )
        }
    }
}

@Composable
private fun PageList(
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        overscrollEffect = null,
        modifier = Modifier
            .fillMaxSize()
            .pageScrollModifiers(showTopAppBar = true, topAppBarScrollBehavior = scrollBehavior),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * The back arrow, which was the same eight lines on three pages.
 *
 * `contentDescription` is the licence page's string and stays it: it is what that page has always
 * announced, odd as the name reads, and every other page now says the same thing because they are
 * the same control.
 */
@Composable
fun PageBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        val layoutDirection = LocalLayoutDirection.current
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.rules_cancel),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

/** The same control as a glass pill, inside the OS4 top bar. */
@Composable
private fun PageBackButtonOs4(onBack: () -> Unit) {
    GlassIconButton(onClick = onBack) {
        val layoutDirection = LocalLayoutDirection.current
        Icon(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                },
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = stringResource(R.string.rules_cancel),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}
