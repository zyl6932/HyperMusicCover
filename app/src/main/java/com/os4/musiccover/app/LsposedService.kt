package com.os4.musiccover.app
import com.os4.musiccover.BuildConfig
import com.os4.musiccover.HotReloadPolicy
import com.os4.musiccover.LogLevel
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's line to LSPosed (libxposed service, API 102), bound by the framework itself through
 * the service library's provider once the app is running.
 *
 * Two things come through it. The log level goes out to every process the module is loaded into,
 * as a remote preferences group they each read (LogLevel, Xp) - the module's own channels reach
 * SystemUI and the wallpaper only. And LSPosed says which processes the module is loaded into
 * right now and whether each runs the installed build, which is what 重启全部作用域 restarts,
 * and what the home page's "模块已更新" card counts and restarts.
 *
 * Without the service - an older framework, or before it has bound - the level stays local and
 * the restart falls back to the scope the APK ships.
 */
object LsposedService {
    private const val PREFS_NAME = "app_settings"

    private val bound = MutableStateFlow<XposedService?>(null)

    /** The bound service, for a page that has to ask again once it arrives. */
    val service: StateFlow<XposedService?> = bound

    @Volatile private var registered = false

    fun init(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        // Before anything can ask for a setting: the local copy is where every page's value and
        // every write goes from stage 5.3 on.
        SettingsStore.init(app)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                // The app's copy is the setting; the group is only where the module reads it. A
                // group that was never written, or was lost with LSPosed's data, gets it again.
                write(service, logLevel(app))
                bound.value = service
                // The channel to the group exists from now on: migrate if nothing has yet, and
                // otherwise push the whole local set back, which is what makes a service that
                // was away for a while whole again.
                SettingsStore.onServiceBound(service)
            }

            override fun onServiceDied(service: XposedService) {
                if (bound.value === service) bound.value = null
                SettingsStore.onServiceDied()
            }
        })
    }

    fun logLevel(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(LogLevel.KEY, LogLevel.NORMAL)

    fun setLogLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(LogLevel.KEY, level)
        }
        bound.value?.let { write(it, level) }
    }

    private fun write(service: XposedService, level: Int) {
        runCatching {
            service.getRemotePreferences(LogLevel.GROUP).edit().putInt(LogLevel.KEY, level).apply()
        }
    }

    /**
     * Every process the module is loaded into right now; null without the service. A binder
     * call into the framework, so not on the main thread.
     */
    fun runningTargets(): List<HookedTarget>? =
        bound.value?.let {
            runCatching { it.runningTargets }.getOrNull()?.also { list ->
                // What the framework says it can reload, for a bug report and for testing a
                // process whose automatic reload did not arrive. Only when it changes: the home
                // page asks on every resume and every count.
                val line = list.joinToString { t ->
                    "${t.processName}/${t.pid}/${t.state}/${t.loadedVersionCode}"
                }
                if (line != lastTargets) {
                    lastTargets = line
                    Log.d("MCHotReloadApp", "targets: $line")
                }
            }
        }

    @Volatile private var lastTargets: String? = null

    /**
     * One reading of what the home page's restart would take: the card counts it and the tap
     * restarts it, so the number shown cannot describe a different set from the one acted on.
     * Taken once and handed from one to the other - read twice, an answer arriving in between
     * (any page's query replaces the last one) could change the second reading.
     *
     * [targets] null and empty are not the same answer - null is no service, empty is nothing
     * stale - which is what lets the restart tell them apart. [sysuiInert] is the page's own
     * answer from SystemUI, which knows it without the service; [sysuiUnlisted] is that SystemUI
     * when no listed pid is it, and the restart kills it by name then.
     */
    class StaleSet(val targets: List<HookedTarget>?, val sysuiInert: Boolean) {
        val sysuiUnlisted = sysuiInert && targets?.none { it.processName == SYSTEMUI } != false
        val count = (targets?.size ?: 0) + if (sysuiUnlisted) 1 else 0

        companion object {
            val NONE = StaleSet(emptyList(), false)
        }
    }

    /** What the restart would take now, given whether SystemUI's answer said inert. */
    fun staleSet(sysuiInert: Boolean): StaleSet = StaleSet(staleTargets(sysuiInert), sysuiInert)

    /**
     * Those of [runningTargets] still on a build older than the one installed, or whose hot
     * reload failed; null without the service.
     *
     * FAILED is included: a process whose reload failed is no more current than one that was
     * never asked, and leaving it out would make the card disappear without anything having
     * changed.
     */
    private fun staleTargets(sysuiInert: Boolean): List<HookedTarget>? {
        // An inert generation is reported up to date on the new build - its adoption failed after
        // LSPosed's part was done. SystemUI says so in its query answer, the others when asked
        // ([refreshInert]).
        val inert = inertProcesses
        // Every process on an older build counts, a supported package's sub-processes included:
        // 高德's :locationservice, the AOD's :keyguardeditor and SystemUI's own extra processes
        // carry the module's hooks too, are refused a reload by process name, and nothing else
        // ever restarts them - the main process reloads in place and is not on this list.
        //
        // RELOADING on the older build is one too. The framework shows a process mid-reload on
        // the new build as soon as the old generation has let go (SystemUI and the wallpaper,
        // 2026-10-28 01:35:50); one still on the old build is either being asked or was refused
        // and is waiting for the retry - 高德's :locationservice sat in that state for forty
        // minutes, 947074 on a phone with 947078 installed, under a green card.
        return runningTargets()?.filter {
            it.state == HookedTarget.State.STALE || it.state == HookedTarget.State.FAILED ||
                (it.state == HookedTarget.State.RELOADING &&
                    it.loadedVersionCode < BuildConfig.VERSION_CODE) ||
                (sysuiInert && it.processName == SYSTEMUI) || it.processName in inert
        }
    }

    private const val SYSTEMUI = "com.android.systemui"

    /** The processes other than SystemUI that said they are inert, as of [refreshInert]. */
    @Volatile private var inertProcesses: Set<String> = emptySet()

    /**
     * Asks every up-to-date process of a supported package whether its module went inert (see
     * [ModuleBridge.inertIn]); SystemUI is left to its own query. Called before the home page
     * counts, so the count and the restart both see the answer.
     */
    suspend fun refreshInert(context: Context) {
        val targets = runningTargets() ?: return
        val pkgs = targets.asSequence()
            .filter { it.state == HookedTarget.State.UP_TO_DATE && it.processName != SYSTEMUI }
            .map { it.processName.substringBefore(':') }
            .filter { it in HotReloadPolicy.SUPPORTED && it != SYSTEMUI }
            .toSet()
        inertProcesses = if (pkgs.isEmpty()) emptySet() else coroutineScope {
            pkgs.map { async { ModuleBridge.inertIn(context, it) } }.awaitAll().flatten().toSet()
        }
        if (inertProcesses.isNotEmpty()) Log.w("MCHotReloadApp", "inert: $inertProcesses")
    }
}
