package com.os4.musiccover

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高德's half of the lock screen map: whether its navigation page is ready to draw one.
 *
 * AmapNavScene, in SystemUI, binds 高德's AMapImmerseNaviService and asks for the map. The service
 * can only answer once 高德's own AJX navigation page has called `NativesModuleImmerseNavi.init`
 * with its page config - the service builds its map view out of that config and that page's
 * AJX context, and with neither it logs "initMapView: empty initConfig" and draws nothing. On
 * ColorOS the page makes that call during walking and cycling navigation. The Java side of 高德
 * never reads any ColorOS state to decide it, so the decision is in the page's script, and the
 * script is identical on both phones; what can differ is what the script is told about the phone
 * (高德 carries isOppo / isOppoDevice / ro.build.version.opporom checks).
 *
 * On this phone the page does call it (2026-09-29, walking navigation), so the script needs no
 * help. This watches, tells SystemUI, and says what it saw through its own probe:
 *   module  - how many NativesModuleImmerseNavi instances the pages built (the module being
 *             created at all means a page asked for it)
 *   init    - how many times a page called init(), and the config it passed; each one tells
 *             SystemUI the map can be drawn (`op immersive --es id amap-nav --es do arm`)
 *   destroy - the page letting go; SystemUI takes the map down
 *
 * `adb shell am broadcast -a com.os4.musiccover.AMAPPROBE` answers in the main process only.
 * With `--ez ask true` - what a freshly started SystemUI sends - it re-sends the start instead,
 * if a page is up.
 * The class names here are 高德's own and unobfuscated: the AJX bridge finds modules by name, so
 * they cannot be minified away.
 */
internal object AmapImmerse {

    @JvmField val PKG = AmapNavScene.PKG

    private const val TAG = "MCAmap: "
    private const val ACTION = AmapNavScene.AMAP_PROBE
    private const val SYSUI = "com.android.systemui"
    private const val SYSUI_PROBE = "com.os4.musiccover.PROBE"
    private const val MODULE = "com.autonavi.minimap.immersenavi.module.NativesModuleImmerseNavi"

    private const val SERVICE = "com.autonavi.minimap.immersenavi.AMapImmerseNaviService"
    private const val PREVIEW_CALLBACK = "setPreviewStateCommandCallback"
    private val CALLBACKS = arrayOf(PREVIEW_CALLBACK, "setUniversalJSONCommandCallback",
        "setMapStateCallback", "setDisplayInfoChangeCallback")

    private val registered = AtomicBoolean(false)
    /** The page has given the module a preview callback, the only way an overview switch reaches it. */
    @Volatile private var previewCallback = false
    private val modules = AtomicInteger()
    private val inits = AtomicInteger()
    @Volatile private var lastConfig: String? = null
    @Volatile private var lastInitAt = 0L
    /** A page has called init and not destroy: the map can be drawn. */
    @Volatile private var armed = false
    @Volatile private var appCtx: Context? = null

    @JvmStatic
    fun handle(cl: ClassLoader) {
        try {
            val instr = Xp.findClass("android.app.Instrumentation", cl)
            Xp.hookAll(instr, "callApplicationOnCreate") { chain ->
                val out = chain.proceed()
                try {
                    val app = chain.args[0] as Application
                    if (Application.getProcessName() == PKG) register(app)
                } catch (t: Throwable) {
                    Xp.log(TAG + "probe not registered: " + t)
                }
                out
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "application hook failed: " + t)
        }
        try {
            val module = Xp.findClass(MODULE, cl)
            Xp.hookAllConstructors(module) { chain ->
                val out = chain.proceed()
                Xp.log(TAG + "module built #" + modules.incrementAndGet())
                out
            }
            Xp.hookAll(module, "init") { chain ->
                lastConfig = chain.args.getOrNull(0) as String?
                lastInitAt = SystemClock.uptimeMillis()
                Xp.log(TAG + "init #" + inits.incrementAndGet() + " config=" + lastConfig)
                val out = chain.proceed()
                armed = true
                tell(true)
                out
            }
            Xp.hookAll(module, "destroy") { chain ->
                Xp.log(TAG + "destroy")
                armed = false
                previewCallback = false
                tell(false)
                chain.proceed()
            }
            // What the page's script asks to be told. The overview switch (10000003) reaches the
            // page only through its preview callback; with none registered 高德 drops the switch
            // without a word ("sendPreviewCommandToAjx: no callbacks registered").
            for (name in CALLBACKS) {
                try {
                    Xp.hookAll(module, name) { chain ->
                        val cb = chain.args.getOrNull(0)
                        if (name == PREVIEW_CALLBACK) previewCallback = cb != null
                        Xp.log(TAG + "page " + name + (if (cb == null) " cleared" else ""))
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    Xp.log(TAG + name + " hook failed: " + t)
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "immerse module hooks failed: " + t)
        }
        try {
            // The service's sendPreviewCommandToAjx: the one static (boolean) method on it.
            val svc = Xp.findClass(SERVICE, cl)
            val preview = svc.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
            for (m in preview) {
                Xp.hook(m) { chain ->
                    Xp.log(TAG + "overview to the page: isPreview=" + chain.args[0] +
                        " previewCallback=" + previewCallback + " config=" + lastConfig)
                    chain.proceed()
                }
            }
            if (preview.isEmpty()) Xp.log(TAG + "no sendPreviewCommandToAjx on " + SERVICE)
        } catch (t: Throwable) {
            Xp.log(TAG + "service hooks failed: " + t)
        }
    }

    /**
     * Tells SystemUI to put the map up or take it down. SystemUI does the binding itself; this
     * is only the moment. An explicit package, so nothing else hears where 高德 is navigating.
     */
    private fun tell(on: Boolean) {
        val ctx = appCtx ?: run {
            Xp.log(TAG + "no context to tell SystemUI " + (if (on) "arm" else "disarm"))
            return
        }
        try {
            ProbeGuard.send(ctx, Intent(SYSUI_PROBE).setPackage(SYSUI)
                .putExtra("op", "immersive")
                .putExtra("id", AmapNavScene.ID)
                .putExtra("do", if (on) "arm" else "disarm")
                .putExtra("src", "amap"))
            Xp.log(TAG + "told SystemUI " + (if (on) "arm" else "disarm"))
        } catch (t: Throwable) {
            Xp.log(TAG + "tell failed: " + t)
        }
    }

    private fun register(ctx: Context) {
        if (!registered.compareAndSet(false, true)) return
        appCtx = ctx
        class Probe : ProbeGuard.Receiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!ProbeGuard.admit(this)) return
                if (i.getBooleanExtra("ask", false)) {
                    Xp.log(TAG + "SystemUI asked, armed=" + armed)
                    if (armed) tell(true)
                    return
                }
                val sb = StringBuilder()
                sb.append("modules=").append(modules.get())
                    .append(" inits=").append(inits.get())
                    .append(" armed=").append(armed)
                    .append(" previewCallback=").append(previewCallback)
                if (lastInitAt != 0L) {
                    sb.append(" lastInit=").append(SystemClock.uptimeMillis() - lastInitAt)
                        .append("ms ago")
                }
                sb.append("\nconfig=").append(lastConfig)
                sb.append('\n').append(Xp.tail(TAG, 40))
                resultData = sb.toString()
            }
        }
        // SystemUI's ask after a restart, and adb.
        ProbeGuard.register(ctx, IntentFilter(ACTION), TAG, { Probe() }, SYSUI, BuildConfig.APPLICATION_ID)
        Xp.log(TAG + "probe registered")
    }
}
