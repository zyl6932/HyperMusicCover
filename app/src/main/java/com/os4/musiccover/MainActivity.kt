package com.os4.musiccover

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.component.PagerNavigationSpringSpec
import com.os4.musiccover.ui.component.liquid.IosLiquidGlassNavigationBar
import com.os4.musiccover.ui.screen.about.AboutPageContent
import com.os4.musiccover.updater.UpdateCheck
import com.os4.musiccover.ui.screen.features.FeaturesPageView
import com.os4.musiccover.ui.screen.home.HomePageView
import com.os4.musiccover.ui.screen.settings.SettingsPageView
import com.os4.musiccover.ui.theme.AppTheme
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    /** Set once the first composition has run, so the splash can hand over to real content. */
    @Volatile
    private var uiReady = false

    /**
     * Deliberately not applied the moment the theme setting changes: it is a configuration
     * change, so the activity is recreated and the switch visibly jumps. Doing it once the app
     * is off screen gets the same result - the splash is right on the next launch - with nobody
     * watching the recreate.
     */
    override fun onStop() {
        super.onStop()
        AppNightMode.apply(this, AppSettings.load(this).themeMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val savedSettings = AppSettings.load(this)

        // Two halves of the same fix for the white flash on launch: paint the window with the
        // colour the UI is about to draw on, and keep the system splash up until it has drawn.
        LaunchBackground.peek(this, savedSettings.themeMode)?.let {
            window.setBackgroundDrawable(ColorDrawable(it))
        }
        // And the third: the splash's own background is a theme attribute, resolved before any
        // of this runs, so it can only be steered through the configuration. Normally a no-op -
        // it only does anything the first launch after the theme mode changes.
        AppNightMode.apply(this, savedSettings.themeMode)
        // A config change (rotation, language) re-runs onCreate with no splash behind it,
        // and holding a blank window then would be a delay the user just sees.
        holdSplashUntilContentIsReady(splashIsShowing = savedInstanceState == null)

        setContent {
            var themeMode by remember {
                mutableStateOf(
                    try {
                        ColorSchemeMode.valueOf(savedSettings.themeMode)
                    } catch (_: Exception) {
                        ColorSchemeMode.System
                    }
                )
            }
            var isFloatingNavbar by remember { mutableStateOf(savedSettings.isFloatingNavbar) }
            var isLiquidGlass by remember { mutableStateOf(savedSettings.isLiquidGlass) }
            var isBlurEnabled by remember { mutableStateOf(savedSettings.isBlurEnabled) }
            var checkUpdate by remember { mutableStateOf(savedSettings.checkUpdate) }

            fun persistState() {
                AppSettings.save(
                    this@MainActivity,
                    AppSettings(
                        themeMode = themeMode.name,
                        isFloatingNavbar = isFloatingNavbar,
                        isLiquidGlass = isLiquidGlass,
                        isBlurEnabled = isBlurEnabled,
                        checkUpdate = checkUpdate,
                        language = LocaleHelper.getSavedLanguage(this@MainActivity).code,
                    )
                )
            }

            AppTheme(themeMode = themeMode) {
                // What the pages actually draw on. Recording it here is what lets the next cold
                // start paint the same colour before Compose exists - including the Monet modes
                // and a theme forced against the system's.
                val surface = MiuixTheme.colorScheme.surface
                LaunchedEffect(surface, themeMode) {
                    LaunchBackground.remember(this@MainActivity, themeMode.name, surface.toArgb())
                    uiReady = true
                }
                // The update check belongs to the app starting, not to the About page being
                // opened - the setting says so, and it is the one moment the answer is worth
                // having before anyone goes looking for it. The About page only reads this.
                LaunchedEffect(Unit) {
                    if (checkUpdate) UpdateCheck.refresh(this@MainActivity)
                }
                MainScreen(
                    themeMode = themeMode,
                    isFloatingNavbar = isFloatingNavbar,
                    isLiquidGlass = isLiquidGlass,
                    isBlurEnabled = isBlurEnabled,
                    checkUpdate = checkUpdate,
                    onThemeModeChange = { themeMode = it; persistState() },
                    onFloatingNavbarChange = { isFloatingNavbar = it; persistState() },
                    onLiquidGlassChange = { isLiquidGlass = it; persistState() },
                    onBlurEnabledChange = { isBlurEnabled = it; persistState() },
                    onCheckUpdateChange = { checkUpdate = it; persistState() },
                )
            }
        }
    }

    /**
     * Android 12+ shows a splash screen on a cold start and takes it away as soon as the app
     * draws its first frame - which, with Compose, is an empty window. Refusing that first draw
     * until the content is composed keeps the splash up instead, so the launch goes straight
     * from the splash to the UI with nothing blank in between.
     *
     * The timeout is the escape hatch: a launch must never be held hostage by a composition that
     * is slower than expected, so past it the window is shown with whatever it has.
     */
    private fun holdSplashUntilContentIsReady(splashIsShowing: Boolean) {
        val content = findViewById<View>(android.R.id.content)
        val start = SystemClock.uptimeMillis()
        // Compose is usually ready before the record has finished spinning. Handing over then
        // would cut the icon animation off mid-turn, so the splash also stays for as long as the
        // animation lasts - it is the whole point of having drawn one.
        val minimumHold = if (splashIsShowing) SPLASH_ANIMATION_MS else 0L
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // Cancelling the draw makes ViewRootImpl schedule another traversal, so this
                    // re-asks every frame on its own until both conditions hold.
                    if (!uiReady) return false
                    if (SystemClock.uptimeMillis() - start < minimumHold) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            }
        )
        content.postDelayed({ uiReady = true }, SPLASH_HOLD_MAX_MS)
    }

    private companion object {
        /** Matches windowSplashScreenAnimationDuration in themes.xml. */
        const val SPLASH_ANIMATION_MS = 700L
        const val SPLASH_HOLD_MAX_MS = 1500L
    }
}

@Composable
private fun MainScreen(
    themeMode: ColorSchemeMode,
    isFloatingNavbar: Boolean,
    isLiquidGlass: Boolean,
    isBlurEnabled: Boolean,
    checkUpdate: Boolean,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onFloatingNavbarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onCheckUpdateChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val items = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_about),
    )
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings, MiuixIcons.Info)

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
            refreshKey++
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val navBarMode = if (!isFloatingNavbar) 0 else if (!isLiquidGlass) 1 else 2

    fun goToPage(index: Int) {
        if (index == selectedIndex) return
        refreshKey++
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize
                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = PagerNavigationSpringSpec,
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }
                if (pagerState.currentPage != index) {
                    pagerState.scrollToPage(index)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    // Back goes to the first page before it leaves the app, the way KernelSU's does: enabled
    // only off the first page, so on the first one the system handles it as usual. Going through
    // goToPage rather than the pager directly means back and tapping the tab are the same
    // action, down to the refresh the pages do when they come to the front.
    BackHandler(enabled = selectedIndex != 0) { goToPage(0) }

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                mode = navBarMode,
                items = items,
                icons = icons,
                selectedIndex = selectedIndex,
                backdrop = backdrop,
                blurActive = isBlurEnabled,
                onItemSelected = { index -> goToPage(index) },
            )
        }
    ) { globalPadding ->
        val navBarHeight = globalPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(Modifier.layerBackdrop(backdrop))
                .background(surfaceColor)
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> HomePageView(
                        isBlurEnabled = isBlurEnabled,
                        refreshKey = refreshKey,
                        extraBottomPadding = navBarHeight,
                    )

                    1 -> FeaturesPageView(
                        isBlurEnabled = isBlurEnabled,
                        refreshKey = refreshKey,
                        extraBottomPadding = navBarHeight,
                    )

                    2 -> SettingsPageView(
                        currentMode = themeMode,
                        onModeChange = onThemeModeChange,
                        isFloatingNavbar = isFloatingNavbar,
                        onFloatingNavbarChange = onFloatingNavbarChange,
                        isLiquidGlass = isLiquidGlass,
                        onLiquidGlassChange = onLiquidGlassChange,
                        isBlurEnabled = isBlurEnabled,
                        onBlurEnabledChange = onBlurEnabledChange,
                        checkUpdate = checkUpdate,
                        onCheckUpdateChange = onCheckUpdateChange,
                        extraBottomPadding = navBarHeight,
                    )

                    3 -> AboutPageContent(
                        openLicensePage = {
                            context.startActivity(Intent(context, LicenseActivity::class.java))
                        },
                        isBlurEnabled = isBlurEnabled,
                        refreshKey = refreshKey,
                        checkUpdate = checkUpdate,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    mode: Int,
    items: List<String>,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
) {
    when (mode) {
        2 -> {
            val navigationItems = remember(items, icons) {
                List(items.size) { i -> NavigationItem(items[i], icons[i]) }
            }
            IosLiquidGlassNavigationBar(
                modifier = Modifier.padding(horizontal = 12.dp),
                items = navigationItems,
                selectedIndex = selectedIndex,
                onItemClick = onItemSelected,
                backdrop = backdrop,
                isBlurActive = blurActive,
            )
        }

        1 -> {
            val floatingBarColor =
                if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
            val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val isDark = isInDarkTheme()
            val floatingHighlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurActive && backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = floatingBarShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.4f)),
                            ),
                        ),
                        highlight = floatingHighlight,
                    )
                } else {
                    Modifier
                },
                color = floatingBarColor,
            ) {
                items.forEachIndexed { index, label ->
                    FloatingNavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = icons[index],
                        label = label,
                        enabled = true
                    )
                }
            }
        }

        else -> {
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .then(
                        if (blurActive && backdrop != null) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.5f)),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(barColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
            ) {
                NavigationBar(color = barColor) {
                    items.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onItemSelected(index) },
                            icon = icons[index],
                            label = label,
                            enabled = true
                        )
                    }
                }
            }
        }
    }
}
