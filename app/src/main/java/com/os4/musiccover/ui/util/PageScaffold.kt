package com.os4.musiccover.ui.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.luminance
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
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
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
 * The one thing callers still own is what goes in the list - and, through [dialogs], the popups
 * that belong to the page: a dialog left at the call site outside this frame samples the theme's
 * flat surface fallback instead of the page, so glass over it has nothing to blur.
 */
@Composable
fun PageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    dialogs: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    if (LocalOs4Ui.current) {
        PageScaffoldOs4(
            title = title,
            extraBottomPadding = extraBottomPadding,
            onBack = onBack,
            actions = actions,
            floatingActionButton = floatingActionButton,
            dialogs = dialogs,
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
            dialogs = dialogs,
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
    dialogs: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    CompositionLocalProvider(LocalPageBackdrop provides backdrop) {
        Scaffold(
            // The default popup host, unlike OS3: the glass dropdowns and dialogs are Compose
            // popups and need a Miuix Scaffold to render into. A page inside MainActivity
            // still hosts them at the root scaffold above (renderInRootScaffold), which is
            // outside every recording; this one is the host for the screens of their own.
            floatingActionButton = floatingActionButton,
            topBar = {
                GlassPageBar(
                    title = title,
                    backdrop = backdrop,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
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
                // No page-level effect here: OS4's pages sit on the same surface as OS3's, and
                // the gradient belongs to the About page, where it already was. Glass reads on
                // that surface through its edges, and the bars blur the content scrolling under
                // them.
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
        // Inside the backdrop scope but outside the recorded body: a dialog here samples the
        // page it covers, and the recording does not contain the dialog itself. A page that
        // leaves its dialogs at the call site instead samples the theme's flat surface
        // fallback, which is what made them look flatter than the demo on standalone screens.
        dialogs()
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
    dialogs: @Composable () -> Unit,
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
    // OS3's dialogs are windows and draw outside this composition; the slot is only here so
    // both branches of PageScaffold take the same call site.
    dialogs()
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

/**
 * The OS4 top bar, for the pages that build their own frame instead of [PageScaffold].
 *
 * Everything OS4-specific about a bar lives here so the three frames cannot drift: the glass
 * style, the progressive band, and the glass back button. `expandable` stays off on the About
 * page, whose large title and its collapse are in the page body and would otherwise fight the
 * bar's own.
 */
@Composable
fun GlassPageBar(
    title: String,
    backdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior?,
    onBack: (() -> Unit)? = null,
    expandable: Boolean = true,
    defaultWindowInsetsPadding: Boolean = true,
    titleAlpha: () -> Float = { 1f },
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The demo's bar, exactly: the library draws its progressive band and, because
    // `progressiveBlur` is on, the bar's buttons sample that rendered scene
    // (`GlassTopAppBarContext.buttonBackdrop`) instead of the raw page. Hand-rolling the band
    // and the scene produced a near miss every time - a different band curve, a different
    // underlay material, a hole under the band's translucent tail - so this takes the library's
    // own path and keeps the two ends of the comparison identical.
    GlassTopAppBar(
        title = title,
        scrollBehavior = scrollBehavior,
        backdrop = backdrop,
        progressiveBlur = true,
        style = GlassDefaults.style(isInDarkTheme()),
        // The demo's bar and buttons use the Middle stroke; the library default is Small.
        stroke = GlassEdgeStrokes.forTheme(
            isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f,
            light = GlassEdgeStrokes.MiddleLight,
            dark = GlassEdgeStrokes.MiddleDark,
        ),
        expandable = expandable,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titleAlpha = titleAlpha,
        navigationIcon = { if (onBack != null) PageBackButtonOs4(onBack) },
        actions = actions,
    )
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
