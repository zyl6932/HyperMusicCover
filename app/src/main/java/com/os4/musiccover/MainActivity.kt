package com.os4.musiccover

import com.os4.musiccover.app.AppNightMode
import com.os4.musiccover.app.AppSettings
import com.os4.musiccover.app.CreditsActivity
import com.os4.musiccover.app.LaunchBackground
import com.os4.musiccover.app.LicenseActivity
import com.os4.musiccover.app.LocaleHelper
import com.os4.musiccover.app.LsposedService
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
import androidx.compose.runtime.derivedStateOf
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
import com.os4.musiccover.ui.welcome.SplashIcon
import com.os4.musiccover.ui.welcome.WelcomeScreen
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
     * Where the system splash drew the app icon, read once as the splash goes away. The welcome
     * page starts its own mark from there, so the hand-over does not jump; measured rather than
     * assumed, because the platform draws that icon itself and a vendor can change where.
     */
    private val splashIcon = mutableStateOf<SplashIcon?>(null)

    /**
     * True once the system splash has actually gone. The welcome page waits for it instead of
     * counting from the process start: the platform keeps the splash up past the icon animation,
     * until the first frame is drawn and its own exit runs, so a timer started at launch played
     * the page's entrance behind the splash and finished before it went.
     */
    private val splashGone = mutableStateOf<Boolean?>(null)

    /** The platform splash object, kept only so the listener can be cleared on destroy. */
    private var activitySplash: Any? = null

    /**
     * Bumped every time this activity comes back to the front, read as a `remember` key in
     * `setContent` below, and handed to the home page as its resume signal.
     *
     * The theme screen is an activity of its own - the house pattern for a sub-screen - so it
     * saves what it changes while this one is stopped, and the settings this page holds were read
     * in `onCreate`. Coming home from a theme change without re-reading them would leave the app
     * drawn in the theme it had on the way out, until the next cold start.
     *
     * The home page reads it for the other half of the same moment: the module can be enabled,
     * disabled or restarted while the app is away, and a page that is up when the app comes back
     * re-asks rather than keeping the answer it had (HomePageView).
     */
    private var settingsEpoch by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        settingsEpoch++
    }

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

    override fun onDestroy() {
        super.onDestroy()
        // The window usually takes the listener with it; clearing is for the times it does not
        // (multi-window, a window kept alive past the activity). By reflection, like the rest of
        // this: the type is SystemApi and not in the SDK this compiles against.
        runCatching {
            activitySplash?.let {
                it.javaClass.getMethod("clearOnExitAnimationListener").invoke(it)
            }
        }
        activitySplash = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LsposedService.init(this)
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
        // A config change (rotation, language) re-runs onCreate with no splash behind it, but
        // the hold only lasts until the composition is ready either way.
        holdSplashUntilContentIsReady()
        // Only a cold start has a splash to hand over; on a recreation the listener would wait
        // for an exit that never comes, on a window that no longer exists.
        if (savedInstanceState == null) readSplashIcon()

        setContent {
            // Re-read on the way back from the theme screen, which saves its own changes while
            // this activity is stopped. Keyed on the epoch so the values follow it, and nothing
            // else does - the pager keeps its page and every other page keeps its state.
            val settings = remember(settingsEpoch) { AppSettings.load(this@MainActivity) }
            var themeMode by remember(settingsEpoch) {
                mutableStateOf(
                    try {
                        ColorSchemeMode.valueOf(settings.themeMode)
                    } catch (_: Exception) {
                        ColorSchemeMode.System
                    }
                )
            }
            var isFloatingNavbar by remember(settingsEpoch) { mutableStateOf(settings.isFloatingNavbar) }
            var isLiquidGlass by remember(settingsEpoch) { mutableStateOf(settings.isLiquidGlass) }
            var isBlurEnabled by remember(settingsEpoch) { mutableStateOf(settings.isBlurEnabled) }
            var welcomeShown by remember(settingsEpoch) { mutableStateOf(settings.welcomeShown) }
            // Read once, not keyed on the epoch: a trip to Settings must not bring the page back
            // by itself - only its own entry does that.
            var showWelcome by remember { mutableStateOf(!settings.welcomeShown) }
            // Whether this showing is the one that comes out of the splash: the first launch,
            // and only then - not a replay from settings, not a recreation. Cleared when the
            // page closes, so a later replay never flies the mark in from the old splash.
            var welcomeFromSplash by remember {
                mutableStateOf(!settings.welcomeShown && savedInstanceState == null)
            }

            fun persistState() {
                AppSettings.save(
                    this@MainActivity,
                    AppSettings(
                        themeMode = themeMode.name,
                        isFloatingNavbar = isFloatingNavbar,
                        isLiquidGlass = isLiquidGlass,
                        isBlurEnabled = isBlurEnabled,
                        language = LocaleHelper.getSavedLanguage(this@MainActivity).code,
                        welcomeShown = welcomeShown,
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
                // opened - it is the one moment the answer is worth having before anyone goes
                // looking for it. The About page only reads this.
                LaunchedEffect(Unit) {
                    UpdateCheck.refresh(this@MainActivity)
                }
                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        themeMode = themeMode,
                        isFloatingNavbar = isFloatingNavbar,
                        isLiquidGlass = isLiquidGlass,
                        isBlurEnabled = isBlurEnabled,
                        resumeKey = settingsEpoch,
                        onThemeModeChange = { themeMode = it; persistState() },
                        onFloatingNavbarChange = { isFloatingNavbar = it; persistState() },
                        onLiquidGlassChange = { isLiquidGlass = it; persistState() },
                        onBlurEnabledChange = { isBlurEnabled = it; persistState() },
                        onShowWelcome = {
                            welcomeFromSplash = false
                            showWelcome = true
                        },
                    )
                    // Over the home page rather than instead of it: the way out is the button
                    // opening a circle onto what is already drawn underneath.
                    if (showWelcome) {
                        WelcomeScreen(
                            splashIcon = splashIcon.value,
                            splashGone = { splashGone.value },
                            fromSplash = welcomeFromSplash,
                            onDone = {
                                welcomeShown = true
                                welcomeFromSplash = false
                                persistState()
                                showWelcome = false
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Takes over the splash's exit: reads the icon's place and size off the splash view, lets
     * the composition see them, and only then lets the splash go. Past API 31 the splash hands
     * its view out here instead of animating itself away.
     */
    private fun readSplashIcon() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            // Built at run time: R8 rewrites constant Class.forName arguments into this app's
            // own obfuscated names (see the module's notes on the same trap).
            //
            // From Android 15 the splash is an object reached through the activity -
            // Activity.getSplashScreen() - and its listener is a method on that object; the old
            // static SplashScreen.setOnExitAnimationListener(Window, listener) is gone, which is
            // why asking for it threw NoSuchMethodException.
            val activityClass = Class.forName(listOf("android", "app", "Activity").joinToString("."))
            val splash = activityClass.getMethod("getSplashScreen").invoke(this) ?: return
            activitySplash = splash
            val splashClass = Class.forName(listOf("android", "window", "SplashScreen").joinToString("."))
            // The listener is a nested interface: its binary name uses a dollar, not a dot.
            val listenerClass = Class.forName(splashClass.name + "\$OnExitAnimationListener")
            // The platform hands its splash view over here instead of animating it away. Both
            // the listener and the provider are SystemApi: present at run time, missing from the
            // public SDK that this compiles against.
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onSplashScreenExit" && args != null && args.isNotEmpty()) {
                    android.util.Log.i(SPLASH_LOG, "splash exit callback")
                    // The argument is the splash's own view; its icon is asked for directly. On
                    // a cold start the first frame can be asked before the icon has been laid
                    // out, so an empty answer is retried a frame later.
                    val splashView = args[0] as? View ?: return@newProxyInstance null
                    var attempts = 0
                    fun take() {
                        val icon = runCatching {
                            splashView.javaClass.getMethod("getIconView").invoke(splashView) as? View
                        }.getOrNull()
                        if ((icon == null || icon.width <= 0) && attempts < 3) {
                            attempts++
                            window.decorView.postDelayed({ take() }, 16)
                            return
                        }
                        if (icon != null && icon.width > 0) {
                            val at = IntArray(2)
                            icon.getLocationOnScreen(at)
                            android.util.Log.i(
                                SPLASH_LOG,
                                "icon w=${icon.width} h=${icon.height} at=${at[0]},${at[1]} (attempts=$attempts)",
                            )
                            splashIcon.value = SplashIcon(
                                centerX = at[0] + icon.width / 2f,
                                centerY = at[1] + icon.height / 2f,
                                size = icon.width.toFloat(),
                            )
                        }
                        // One frame for the welcome page to take the new place before the splash
                        // goes, so the mark is never drawn in the wrong spot.
                        window.decorView.postDelayed({
                            val removed = runCatching { splashView.javaClass.getMethod("remove").invoke(splashView) }
                            android.util.Log.i(SPLASH_LOG, "splash exit: removed=$removed")
                            // The view letting go of the window is the splash going, and it is
                            // what the page starts on. remove() takes effect on the next frame
                            // or two - measured at 27ms - so there is nothing to wait for past
                            // that. A fixed wait after remove() (300ms was tried) only left
                            // the mark standing still on an empty screen before it flew.
                            val removedAt = SystemClock.uptimeMillis()
                            val done = object : Runnable {
                                override fun run() {
                                    val elapsed = SystemClock.uptimeMillis() - removedAt
                                    // Not for ever: a view that never detaches would hold the
                                    // page still for the rest of the launch.
                                    if (splashView.isAttachedToWindow &&
                                        elapsed < SPLASH_DETACH_MAX_MS
                                    ) {
                                        window.decorView.postDelayed(this, 16)
                                        return
                                    }
                                    android.util.Log.i(SPLASH_LOG, "splash gone after ${elapsed}ms")
                                    splashGone.value = true
                                }
                            }
                            window.decorView.postDelayed(done, 16)
                        }, 32)
                    }
                    take()
                }
                null
            }
            splashClass
                .getMethod("setOnExitAnimationListener", listenerClass)
                .invoke(splash, listener)
            // There is a splash and it is being waited for. Without this the state stays null,
            // which reads as "no signal" and lets the entrance play behind the splash.
            splashGone.value = false
        } catch (e: Throwable) {
            // No listener: the splash goes by itself and the welcome page starts from the
            // platform's own numbers instead - splashGone stays null, which the page reads as
            // "no signal".
            android.util.Log.w(SPLASH_LOG, "splash listener unavailable", e)
        }
        // The listener is the signal. This is only for a platform that takes the listener and
        // never calls it, which would otherwise leave the page waiting for ever.
        window.decorView.postDelayed({
            if (splashGone.value != true) {
                android.util.Log.w(SPLASH_LOG, "splash listener never fired; falling back")
                splashGone.value = true
            }
        }, SPLASH_GONE_TIMEOUT_MS)
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
    private fun holdSplashUntilContentIsReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    // Cancelling the draw makes ViewRootImpl schedule another traversal, so this
                    // re-asks every frame on its own until the condition holds.
                    if (!uiReady) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            }
        )
        content.postDelayed({ uiReady = true }, SPLASH_HOLD_MAX_MS)
    }

    private companion object {
        const val SPLASH_HOLD_MAX_MS = 1500L

        /** How long to wait for a splash listener that was accepted but never called. */
        const val SPLASH_GONE_TIMEOUT_MS = 2000L

        const val SPLASH_LOG = "HMC-Splash"

        /** The longest wait for the splash view to leave the window on its own. */
        const val SPLASH_DETACH_MAX_MS = 1000L
    }
}

@Composable
private fun MainScreen(
    themeMode: ColorSchemeMode,
    isFloatingNavbar: Boolean,
    isLiquidGlass: Boolean,
    isBlurEnabled: Boolean,
    resumeKey: Int,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onFloatingNavbarChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onShowWelcome: () -> Unit,
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val items = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_about),
    )
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.Settings, MiuixIcons.Info)

    /**
     * One flag per page rather than one counter shared by all four.
     *
     * The pages re-ask the module when they come to the front, and a single counter bumped on
     * every tab change made that *every* page: a tap re-composed and re-queried the pages nobody
     * asked about, in the same frames the transition was trying to fill, and one of those queries
     * is an ordered broadcast into SystemUI.
     *
     * `derivedStateOf` is what keeps it to the one page. Reading `pagerState.currentPage` from
     * the page would make every page a subscriber to every page change; comparing it here and
     * publishing only the answer means a page hears about it exactly when its own answer flips.
     * The lambdas are remembered so the parameter is the same instance every recomposition -
     * otherwise every page is unskippable and the split buys nothing.
     */
    val onHomePage = remember { derivedStateOf { pagerState.currentPage == 0 } }
    val onAboutPage = remember { derivedStateOf { pagerState.currentPage == 3 } }
    val isHomeCurrent: () -> Boolean = remember(onHomePage) { { onHomePage.value } }
    val isAboutCurrent: () -> Boolean = remember(onAboutPage) { { onAboutPage.value } }

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
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
                // Gated on the setting, not just on the bar that samples it. Recording a layer
                // is a second render of everything inside it, and with blur off nothing was
                // reading this one - the bar had already switched to its opaque colour.
                .then(if (isBlurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
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
                        isCurrent = isHomeCurrent,
                        resumeKey = resumeKey,
                        extraBottomPadding = navBarHeight,
                    )

                    1 -> FeaturesPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                    )

                    2 -> SettingsPageView(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                        onShowWelcome = onShowWelcome,
                    )

                    3 -> AboutPageContent(
                        openLicensePage = {
                            context.startActivity(Intent(context, LicenseActivity::class.java))
                        },
                        openCreditsPage = {
                            context.startActivity(Intent(context, CreditsActivity::class.java))
                        },
                        isBlurEnabled = isBlurEnabled,
                        isCurrent = isAboutCurrent,
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
