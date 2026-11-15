// SPDX-License-Identifier: AGPL-3.0-or-later
// 锁屏状态小组件：天气 / 电量大卡，闹钟 / 日出日落 / 湿度 / AQI / 体感 / 风向 / 步数 / 站立小圆片。
// 从 HyperChanger 移植；场景隐藏、位置算法对照 HC LockscreenWidget.kt；材质复用 HMC IslandMaterial。
package com.os4.musiccover.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.os4.musiccover.Gen
import com.os4.musiccover.Settings as ModuleSettings
import com.os4.musiccover.Xp
import com.os4.musiccover.island.IslandMaterial
import com.os4.musiccover.island.MiniPlayerRuntime
import com.os4.musiccover.island.MiniPlayerScene
import com.os4.musiccover.lyrics.LockLyrics
import java.lang.ref.WeakReference
import java.util.Calendar
import kotlin.math.roundToInt

/** 运行时入口：钩住 MiuiShortcutController.addShortcutViews，并安装场景状态 hook。 */
object StatusWidgetRuntime {

    private const val SHORTCUT_CONTROLLER =
        "com.android.keyguard.shortcut.MiuiShortcutController"

    @JvmStatic
    fun install(classLoader: ClassLoader) {
        runCatching {
            val cls = Xp.findClass(SHORTCUT_CONTROLLER, classLoader)
            Xp.hookAll("sysui.status.widget.shortcut_views", cls, "addShortcutViews") { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? View)?.let { root ->
                    scheduleInstall(root, chain.thisObject, classLoader)
                }
                result
            }
        }.onFailure { Xp.w("StatusWidget: shortcut hook unavailable: $it") }
        Xp.log("StatusWidget: runtime installed, shortcut hook registered")
    }

    private fun scheduleInstall(root: View, controller: Any?, loader: ClassLoader) {
        val task = Runnable {
            runCatching { attach(root, controller, loader) }
                .onFailure { Xp.w("StatusWidget: attach failed: $it") }
        }
        task.run()
        Gen.post(task)
        Gen.postDelayed(task, 250L)
    }

    /** host 上标记已挂载控制器的 tag key，保证同一宿主只挂一次（三趟兜底时序幂等）。 */
    private const val HOST_TAG = 0x7f0d1357

    private fun attach(root: View, controller: Any?, loader: ClassLoader) {
        val host = root.rootView as? ViewGroup ?: run {
            Xp.w("StatusWidget: attach: host rootView is not a ViewGroup")
            return
        }
        // 同一宿主已挂过：跳过，避免三趟兜底叠加出多个控制器/多层视图。
        if (host.getTag(HOST_TAG) is LockStatusWidgetController) {
            Xp.log("StatusWidget: attach: already mounted on host, skip")
            return
        }
        val shortcuts = resolveShortcuts(controller) ?: findShortcuts(root)
        if (shortcuts == null) {
            Xp.w("StatusWidget: attach: left/right shortcuts not found")
            return
        }
        if (shortcuts.first === shortcuts.second) {
            Xp.w("StatusWidget: attach: left and right shortcut are the same view")
            return
        }
        Xp.log("StatusWidget: attach: shortcuts resolved, controller attached")
        host.clipChildren = false
        host.clipToPadding = false
        dumpBg(shortcuts.first, "leftShortcut")
        dumpBg(shortcuts.second, "rightShortcut")
        fun visit(v: View) {
            val cn = v.javaClass.name
            if (cn.contains("Media") || cn.contains("Island") || cn.contains("MiniPlayer") ||
                cn.contains("media")) dumpBg(v, "media")
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(host)
        val c = LockStatusWidgetController(host, shortcuts.first, shortcuts.second, loader)
        host.setTag(HOST_TAG, c)
    }

    private fun dumpBg(v: View, tag: String) {
        val bg = v.background
        Xp.log("StatusWidget: bg[$tag] view=${v.javaClass.simpleName} " +
            "bg=${bg?.javaClass?.simpleName} w=${v.width} h=${v.height}")
    }

    private fun resolveShortcuts(controller: Any?): Pair<View, View>? = runCatching {
        val method = controller?.javaClass?.methods?.firstOrNull {
            it.name == "onSystemUIAction\$1" && it.parameterCount == 2
        } ?: return@runCatching null
        fun get(left: Boolean): View? = method.invoke(
            controller,
            Bundle().apply { putBoolean("isLeftShortcutView", left) },
            "getShortcutView"
        ) as? View
        val l = get(true) ?: return@runCatching null
        val r = get(false) ?: return@runCatching null
        l to r
    }.getOrNull()

    private fun findShortcuts(root: View): Pair<View, View>? {
        var left: View? = null
        var right: View? = null
        fun visit(view: View) {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (name == "shortcut_view_left_layout") left = view
            if (name == "shortcut_view_right_layout") right = view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        val l = left ?: return null
        val r = right ?: return null
        return l to r
    }
}

/**
 * 场景状态：复用已在真机验证过的 MiniPlayerScene。不读 ShadeLayer.expansion()——那是封面遮罩
 * 面板进度，锁屏残留会卡在 1.0，把正常锁屏误判成面板展开而挡掉卡片。
 */
private object SceneState {
    fun shouldShow(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = runCatching {
        ModuleSettings.WIDGET_ENABLED.on() &&
            !MiniPlayerScene.keyguardGoingAway &&
            !MiniPlayerScene.aodActive &&
            !MiniPlayerScene.controlCenterIsActive &&
            !MiniPlayerScene.blocksMiniPlayer &&
            !(ModuleSettings.WIDGET_HIDE_LYRICS.on() && LockLyrics.wantsAttached())
    }.getOrDefault(false)
}

/** 单个锁屏根上的控制器：持有视图、轮询数据、按场景显隐、摆放坐标。 */
private class LockStatusWidgetController(
    private val host: ViewGroup,
    private val leftShortcut: View,
    private val rightShortcut: View,
    private val loader: ClassLoader
) {
    private val context: Context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private var view: LockStatusWidgetView? = null
    private var lastShown: Boolean? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateAndPosition() }

    init {
        host.rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        leftShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAndPosition() }
        rightShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAndPosition() }
        handler.post(refreshTask)
    }

    fun refresh() {
        updateVisibility()
        val w = view ?: return
        if (w.visibility != View.VISIBLE) return
        Xp.log("StatusWidget: refresh rebind")
        w.bind(WidgetData.read(context, loader))
        // bind 会重建每个面板的材质层，新图层需要重新穿一次系统玻璃，否则背景透明。
        w.dress(loader)
        position()
    }

    private fun updateAndPosition() {
        updateVisibility()
        val w = view ?: return
        if (w.visibility == View.VISIBLE) position()
    }

    private fun updateVisibility() {
        val show = SceneState.shouldShow(context) && !isBouncerShowing()
        if (lastShown != show) {
            Xp.log("StatusWidget: show=$show goingAway=${MiniPlayerScene.keyguardGoingAway} " +
                "aod=${MiniPlayerScene.aodActive} center=${MiniPlayerScene.controlCenterIsActive} " +
                "editor=${MiniPlayerScene.blocksMiniPlayer}")
            lastShown = show
        }
        val w = view
        if (!show) {
            if (w != null && w.visibility != View.GONE) w.visibility = View.GONE
            return
        }
        if (w == null) {
            val widget = LockStatusWidgetView(context).also { view = it }
            host.addView(widget, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            widget.dress(loader)
            Xp.log("StatusWidget: view created and attached to host")
        } else if (w.visibility != View.VISIBLE) {
            w.visibility = View.VISIBLE
        }
    }

    /** 左右快捷键中心定位；有通知时避让到第一条通知上方；直接贴通知/岛实时位置，不自起动画。 */
    private fun position() {
        val w = view ?: return
        if (!w.isAttachedToWindow || w.visibility != View.VISIBLE) return
        val hostLoc = IntArray(2).also(host::getLocationOnScreen)
        val lLoc = IntArray(2).also(leftShortcut::getLocationOnScreen)
        val rLoc = IntArray(2).also(rightShortcut::getLocationOnScreen)
        // 卡片整体仍以左右快捷键中心平均点定位；大卡内部内容靠左。
        val centerX = ((lLoc[0] + leftShortcut.width / 2f) +
            (rLoc[0] + rightShortcut.width / 2f)) / 2f - hostLoc[0]
        val centerY = ((lLoc[1] + leftShortcut.height / 2f) +
            (rLoc[1] + rightShortcut.height / 2f)) / 2f - hostLoc[1]
        if (w.width <= 0 || w.height <= 0) return
        w.translationX = centerX - w.width / 2f - w.left
        // 无通知：悬在快捷键上方 72dp；有通知：顶到第一条通知上方留 16dp 间距，取更靠上者。
        val normalTargetY = centerY - w.height / 2f - dp(72) - w.top
        val notifyTargetY = firstVisibleNotificationTop()?.let { top ->
            top - dp(16) - w.height - w.top
        }
        w.translationY = notifyTargetY?.let { minOf(normalTargetY, it) } ?: normalTargetY
    }

    private var notificationStack: WeakReference<View>? = null

    private fun findViewByIdName(root: View?, names: Set<String>): View? {
        root ?: return null
        var found: View? = null
        fun visit(v: View) {
            if (found != null) return
            val nm = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (nm != null && nm in names) { found = v; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return found
    }

    private fun isVisibleForAvoidance(v: View): Boolean =
        v.visibility == View.VISIBLE && v.alpha > 0.01f &&
            v.width > 0 && v.height > dp(24)

    /** 第一条可见通知（含系统媒体头）相对 host 的顶部 y；没有则返回 null。 */
    private fun firstVisibleNotificationTop(): Float? {
        val stack = notificationStack?.get()?.takeIf { it.isAttachedToWindow }
            ?: findViewByIdName(host.rootView, setOf("notification_stack_scroller"))
                ?.also { notificationStack = WeakReference(it) }
        var top: Int? = null
        fun record(v: View) {
            val loc = IntArray(2).also(v::getLocationOnScreen)
            top = top?.let { minOf(it, loc[1]) } ?: loc[1]
        }
        fun visitRow(v: View) {
            if (!isVisibleForAvoidance(v)) return
            if (v.javaClass.name.contains("ExpandableNotificationRow")) { record(v); return }
            if (v is ViewGroup) for (i in 0 until v.childCount) visitRow(v.getChildAt(i))
        }
        fun visitMedia(v: View) {
            if (!isVisibleForAvoidance(v)) return
            var t: Class<*>? = v.javaClass
            var isMedia = false
            while (t != null) {
                if (t.name == "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView") {
                    isMedia = true; break
                }
                t = t.superclass
            }
            if (isMedia) { record(v); return }
            if (v is ViewGroup) for (i in 0 until v.childCount) visitMedia(v.getChildAt(i))
        }
        stack?.takeIf(::isVisibleForAvoidance)?.let(::visitRow)
        host.rootView?.let(::visitMedia)
        val notifyTop = top ?: return null
        val hostLoc = IntArray(2).also(host::getLocationOnScreen)
        return notifyTop - hostLoc[1].toFloat()
    }

    /** 密码/PIN 输入界面仍算锁屏，但卡片要藏起来。 */
    private fun isBouncerShowing(): Boolean {
        var found = false
        fun visit(v: View) {
            if (found || v.visibility != View.VISIBLE || v.alpha <= 0.01f ||
                v.width <= 0 || v.height <= 0
            ) return
            val n = v.javaClass.name.lowercase()
            if (n.contains("bouncer") || n.contains("keyguardpassword") ||
                n.contains("keyguardpin") || n.contains("pinbasedinput") ||
                n.contains("securitycontainer") || n.contains("passwordview")
            ) {
                found = true
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(host.rootView)
        return found
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}

// ----------------------------------------------------------------- widget model

/** 可选组件：big 是横卡，否则是圆形小片。布局规则：最多 2 大；2 大时不放小，1 大时最多 2 小。 */
private enum class WidgetSlot(
    val big: Boolean,
    val setting: ModuleSettings.Bool
) {
    WEATHER(true, ModuleSettings.WIDGET_WEATHER),
    BATTERY(true, ModuleSettings.WIDGET_BATTERY),
    SUN(false, ModuleSettings.WIDGET_SUN),
    STEPS(false, ModuleSettings.WIDGET_STEPS),
    HUMIDITY(false, ModuleSettings.WIDGET_HUMIDITY),
    AQI(false, ModuleSettings.WIDGET_AQI),
    FEELS(false, ModuleSettings.WIDGET_FEELS),
    WIND(false, ModuleSettings.WIDGET_WIND),
    STAND(false, ModuleSettings.WIDGET_STAND),
    ALARM(false, ModuleSettings.WIDGET_ALARM);

    /** 这一项在当前数据下要显示什么；valid=false 表示数据缺失，直接跳过。 */
    fun content(snap: Snapshot): SlotContent = when (this) {
        WEATHER -> snap.weather?.let {
            SlotContent("${it.temp} ${it.condition}", it.city, it.highLow, true,
                iconId = it.iconId)
        } ?: SlotContent("--°", "", "", false)

        BATTERY -> snap.battery?.let {
            SlotContent("$it%", snap.device ?: Build.MODEL ?: "电量", null, true,
                level = it)
        } ?: SlotContent("--%", "", null, false)

        ALARM -> snap.alarm?.let {
            SlotContent(it, "闹钟", null, true, iconResName = "ic_alarm")
        } ?: SlotContent("--:--", "闹钟", null, false)

        SUN -> {
            val w = snap.weather
            val day = w != null && isDaytime(w.sunriseMin, w.sunsetMin)
            val label = if (day) "日出" else "日落"
            val value = if (day) fmtMin(w?.sunriseMin) else fmtMin(w?.sunsetMin)
            val resName = if (day) "weather_icon_sun_up" else "weather_icon_sun_down"
            SlotContent(value ?: "--:--", label, null, w != null, iconResName = resName)
        }

        HUMIDITY -> {
            val h = snap.weather?.humidity
            SlotContent(h ?: "--", "湿度", null, h != null)
        }

        AQI -> {
            val a = snap.weather?.aqi
            SlotContent(a ?: "--", "AQI", null, a != null)
        }

        FEELS -> {
            val f = snap.weather?.feelsLike
            SlotContent(f ?: "--°", "体感", null, f != null)
        }

        WIND -> {
            val w = snap.weather
            SlotContent(w?.wind ?: "--", "风向", null, w != null,
                iconId = w?.windIconId)
        }

        STEPS -> snap.health?.steps?.let {
            SlotContent("$it", "步数", null, true, iconResName = "health_icon_step_count_25")
        } ?: SlotContent("--", "步数", null, false)

        STAND -> snap.health?.stand?.let { SlotContent("$it", "站立", null, true) }
            ?: SlotContent("--", "站立", null, false)
    }

    companion object {
        private fun isDaytime(sunrise: Int?, sunset: Int?): Boolean {
            val sr = sunrise ?: return true
            val ss = sunset ?: return true
            val now = Calendar.getInstance().run {
                get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
            }
            return now in sr..ss
        }

        private fun fmtMin(min: Int?): String? {
            if (min == null || min <= 0) return null
            return "%02d:%02d".format(min / 60, min % 60)
        }
    }
}

/** 一个面板要摆的内容：主值、副标签、大卡第三行（高低温/进度条）、可选图标与电量比例。 */
private data class SlotContent(
    val primary: String,
    val secondary: String?,
    val tertiary: String?,
    val valid: Boolean,
    val iconId: Int? = null,
    val iconResName: String? = null,
    val level: Int? = null,
)

// ------------------------------------------------------------------ view

/**
 * 容器：外层负责整体居中定位；内部垂直排两行（大卡一行、小片一行）。每个面板独立材质层，
 * 复用 HMC 系统柔光玻璃，面板随内容 wrap_content 伸缩。
 */
private class LockStatusWidgetView(context: Context) : FrameLayout(context) {

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val materialLayers = ArrayList<ImageView>()

    init {
        addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(snap: Snapshot) {
        val chosen = choose(snap)
        root.removeAllViews()
        materialLayers.clear()
        if (chosen.isEmpty()) {
            visibility = GONE
            return
        }
        // 大卡与小片在同一行并排（对照 HC 的单条 surface 行）。
        chosen.forEachIndexed { i, (slot, content) ->
            val panel = makePanel(slot, content)
            val lp = if (slot.big)
                LinearLayout.LayoutParams(dp(151), dp(72))
            else
                LinearLayout.LayoutParams(dp(72), dp(72))
            root.addView(panel, lp)
            if (i < chosen.size - 1) {
                val nextBig = chosen[i + 1].first.big
                val gap = if (slot.big && nextBig) dp(27) else dp(12)
                root.addView(spacer(gap))
            }
        }
    }

    /** 规则：大卡最多 2；选了 2 大就不放小片；1 大时小片最多 2；0 大小片最多 2。 */
    private fun choose(snap: Snapshot): List<Pair<WidgetSlot, SlotContent>> {
        val all = WidgetSlot.entries
            .filter { it.setting.on() }
            .map { it to it.content(snap) }
            .filter { it.second.valid }
        val big = all.filter { it.first.big }.take(2)
        val small = if (big.size == 2) emptyList() else all.filter { !it.first.big }.take(2)
        return big + small
    }

    private fun makePanel(slot: WidgetSlot, content: SlotContent): View {
        val panel = FrameLayout(context)
        // 小片背景固定铺；两个大卡各自独立开关。
        val bigBgOn = when (slot) {
            WidgetSlot.WEATHER -> ModuleSettings.WIDGET_WEATHER_BG.on()
            WidgetSlot.BATTERY -> ModuleSettings.WIDGET_BATTERY_BG.on()
            else -> false
        }
        val bgOn = if (slot.big) bigBgOn else true
        if (bgOn) {
            // 圆角裁切设在材质层本身（照 HC），系统玻璃的边框才跟着圆角走，而不是父面板裁一个方边。
            val material = ImageView(context)
            material.scaleType = ImageView.ScaleType.FIT_XY
            material.clipToOutline = true
            material.outlineProvider = roundedOutline(dp(36).toFloat())
            materialLayers.add(material)
            panel.addView(material, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        if (slot.big) {
            // 大横卡 151dp 宽、72dp 高；胶囊圆角由材质层自身 outline 控制。
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            val ink = ink()
            when (slot) {
                WidgetSlot.WEATHER -> {
                    val top = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    content.iconId?.let { id ->
                        runCatching { context.getDrawable(id) }.getOrNull()?.let { icon ->
                            val image = ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                                setImageDrawable(icon)
                            }
                            top.addView(image, LinearLayout.LayoutParams(dp(20), dp(20)))
                        }
                    }
                    val title = line(content.primary, 15.3f, ink, bold = true)
                    top.addView(title, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(4) })
                    col.addView(top)
                    if (!content.secondary.isNullOrBlank())
                        col.addView(line(content.secondary, 12.24f, ink, false, left = true))
                    if (!content.tertiary.isNullOrBlank())
                        col.addView(line(content.tertiary, 11.2f, ink, false, left = true))
                }
                WidgetSlot.BATTERY -> {
                    val top = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val systemIcon = resolveSystemDrawable(listOf(
                        "ic_battery", "ic_battery_level", "ic_lock_battery"
                    ))
                    if (systemIcon != null) {
                        val image = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setImageDrawable(systemIcon)
                        }
                        top.addView(image, LinearLayout.LayoutParams(dp(18), dp(18)))
                    } else {
                        // 取不到系统图标时，自绘手机轮廓（细边框圆角矩形）兜底。
                        top.addView(phoneOutline(),
                            LinearLayout.LayoutParams(dp(12), dp(18)))
                    }
                    val title = line(content.primary, 15.3f, ink, bold = true)
                    top.addView(title, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { leftMargin = dp(5) })
                    col.addView(top)
                    if (!content.secondary.isNullOrBlank())
                        col.addView(line(content.secondary, 12.24f, ink, false, left = true))
                    val bar = batteryBar(content.level)
                    col.addView(bar, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
                    ).apply { topMargin = dp(3) })
                }
                else -> {
                    col.addView(line(content.primary, 15.3f, ink, bold = true, left = true))
                    if (!content.secondary.isNullOrBlank())
                        col.addView(line(content.secondary, 12.24f, ink, false, left = true))
                    if (!content.tertiary.isNullOrBlank())
                        col.addView(line(content.tertiary, 11.2f, ink, false, left = true))
                }
            }
            panel.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            panel.layoutParams = FrameLayout.LayoutParams(dp(151), dp(72))
        } else {
            // 小片宽 64dp、高 72dp（高度对齐大卡）；圆角矩形由材质层自身 outline 控制。
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val ink = ink()
            // HC 小片：图标在上，标签次之，数值在下。
            resolveIcon(content)?.let { icon ->
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageDrawable(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(ink)
                }
                col.addView(image, LinearLayout.LayoutParams(dp(18), dp(18)))
                if (!content.secondary.isNullOrBlank())
                    col.addView(line(content.secondary, 10f, ink, false))
            } ?: run {
                if (!content.secondary.isNullOrBlank())
                    col.addView(line(content.secondary, 10f, ink, false))
            }
            col.addView(line(content.primary, 12.24f, ink, true))
            panel.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            panel.layoutParams = FrameLayout.LayoutParams(dp(72), dp(72))
        }
        return panel
    }

    private fun line(text: String, sizeSp: Float, color: Int, bold: Boolean,
                     left: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            setShadowLayer(2f, 0f, 1f, Color.argb(150, 0, 0, 0))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = if (left) Gravity.START else Gravity.CENTER
        }

    /** 自绘手机轮廓：透明填充 + 白色细描边圆角矩形。 */
    private fun phoneOutline(): View {
        val stroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.WHITE)
            cornerRadius = dp(3).toFloat()
        }
        return View(context).apply { background = stroke }
    }

    /** 先取时钟资源 id，再按系统资源名兜底。 */
    private fun resolveIcon(c: SlotContent): Drawable? =
        c.iconId?.let { id -> runCatching { context.getDrawable(id) }.getOrNull() }
            ?: c.iconResName?.let { resolveSystemDrawable(listOf(it)) }

    private fun resolveSystemDrawable(names: List<String>): Drawable? = runCatching {
        names.firstNotNullOfOrNull { name ->
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            if (id != 0) context.getDrawable(id) else null
        }
    }.getOrNull()

    /** 电量进度条：灰色轨道全宽，白色填充按百分比，对照参考图。 */
    private fun batteryBar(level: Int?): View {
        val bar = FrameLayout(context)
        val track = View(context).apply { background = pill(Color.argb(90, 140, 140, 140)) }
        val fill = View(context).apply { background = pill(Color.WHITE) }
        bar.addView(track, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(10)))
        val contentWidth = dp(123)
        val fillWidth = if (level == null) 0 else {
            (contentWidth * level / 100f).roundToInt()
                .coerceAtLeast(if (level > 0) dp(5) else 0)
        }
        bar.addView(fill, FrameLayout.LayoutParams(fillWidth, dp(10)))
        return bar
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(9).toFloat()
    }

    private fun ink(): Int = runCatching { IslandMaterial.ink(context)[0] }
        .getOrDefault(Color.WHITE)

    /** 穿系统柔光玻璃；失败由 material 内部落纯色兜底。 */
    fun dress(loader: ClassLoader) {
        materialLayers.forEach { layer ->
            runCatching { MiniPlayerRuntime.material(layer, loader) }
                .onFailure { Xp.w("StatusWidget: material unavailable: $it") }
        }
    }

    private fun spacer(dp: Int, vertical: Boolean = false): View = View(context).apply {
        layoutParams = (if (vertical)
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp)
        else
            LinearLayout.LayoutParams(dp, LayoutParams.MATCH_PARENT))
    }

    private fun linearWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun verticalWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun ovalOutline() = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}

// ------------------------------------------------------------------ data

/** 数据读取：天气走 MIUI 时钟反射；健康走时钟 health bean；电池走 BatteryManager；闹钟走系统设置。 */
private object WidgetData {
    private const val CLOCK_DATA_UTILS = "com.miui.clock.utils.DataUtils"
    private const val NEXT_ALARM = "next_alarm_formatted"

    fun read(context: Context, loader: ClassLoader): Snapshot = Snapshot(
        weather = readWeather(context, loader),
        battery = readBattery(context),
        device = readDeviceName(),
        alarm = readAlarm(context),
        health = readHealth(context, loader)
    )

    private fun readWeather(context: Context, loader: ClassLoader): WeatherInfo? = runCatching {
        val utils = Class.forName(CLOCK_DATA_UTILS, false, loader)
        val getter = utils.getMethod("getWeatherBean", String::class.java, WeakReference::class.java)
        val bean = listOf("2", "1").asSequence()
            .mapNotNull { type ->
                runCatching { getter.invoke(null, type, WeakReference(context)) }.getOrNull()
            }.firstOrNull() ?: return@runCatching null

        fun call(name: String, vararg args: Any?): Any? = runCatching {
            val method = bean.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: return@runCatching null
            method.invoke(bean, *args)
        }.getOrNull()

        val tempValid = call("getTemperatureValid") as? Boolean ?: true
        val temp = if (tempValid) {
            "${(call("getTemperature") as? Number)?.toInt() ?: 0}°"
        } else "--°"
        val condition = (call("getDescription") as? String).orEmpty().ifBlank { "晴" }
        val city = (call("getCityName") as? String).orEmpty().ifBlank { "天气" }
        val high = (call("getHighestTemperature") as? Number)?.toInt()
        val low = (call("getLowestTemperature") as? Number)?.toInt()
        val highLow = if (high != null && low != null) "高 $high°  低 $low°" else null
        val sunrise = (call("getSunriseMinuteTime") as? Number)?.toInt()
        val sunset = (call("getSunsetMinuteTime") as? Number)?.toInt()
        val humidity = (call("getHumidity") as? Number)?.toInt()
            ?.takeIf { it in 0..100 }?.let { "$it%" }
        val aqi = if (call("isAQIDateValid") as? Boolean == true)
            (call("getAQILevel") as? String).orEmpty().ifBlank { null } else null
        val feels = if (call("getFeelTemperatureValid") as? Boolean == true)
            "${(call("getSomatosensoryTemperature") as? Number)?.toInt() ?: 0}°" else null
        val windDir = (call("getWindDescResIdFull") as? Number)?.toInt()
            ?.let { id -> runCatching { context.getString(id) }.getOrNull() }.orEmpty()
        val windStrength = (call("getWindStrength") as? String).orEmpty()
        val wind = if (windDir.isNotBlank() && windStrength.isNotBlank())
            "$windDir ${windStrength}级" else null
        val windIconId = (call("getWindIconResId") as? Number)?.toInt()?.takeIf { it != 0 }

        val nowMin = Calendar.getInstance().run {
            get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
        }
        val night = sunrise != null && sunset != null && (nowMin < sunrise || nowMin > sunset)
        val iconId = (call("getIconResId", night, false) as? Number)?.toInt()?.takeIf { it != 0 }

        WeatherInfo(temp, condition, city, highLow, sunrise, sunset,
            humidity, aqi, feels, wind, iconId, windIconId)
    }.getOrNull()

    private fun readBattery(context: Context): Int? = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: run {
                val sticky = context.registerReceiver(
                    null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level in 0..100 && scale > 0) Math.round(level * 100f / scale) else null
            }
    }.getOrNull()

    /** 友好机型名：ro.product.marketname 优先，回退 Build.MODEL（对照 HC readRoProductMarketName）。 */
    private fun readDeviceName(): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = (clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.product.marketname", "") as? String).orEmpty().trim()
        value.ifBlank { Build.MODEL?.trim().orEmpty().ifBlank { null } }
    }.getOrNull()

    private fun readAlarm(context: Context): String? =
        android.provider.Settings.System.getString(context.contentResolver, NEXT_ALARM)
            ?.trim()?.ifEmpty { null }

    private fun readHealth(context: Context, loader: ClassLoader): HealthInfo? = runCatching {
        val utils = Class.forName(CLOCK_DATA_UTILS, false, loader)
        val healthBean = Class.forName("com.miui.clock.module.HealthBean", false, loader)
        val getter = utils.methods.firstOrNull { method ->
            method.name == "getHealthBean" && method.parameterCount == 3 &&
                method.parameterTypes[0] == WeakReference::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2].isAssignableFrom(healthBean)
        } ?: return@runCatching null
        fun count(type: Int, getterName: String): Int? {
            val bean = getter.invoke(null, WeakReference(context), type, null) ?: return null
            val value = bean.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterCount == 0
            }?.invoke(bean) as? Number ?: return null
            return value.toInt().takeIf { n -> n >= 0 }
        }
        HealthInfo(count(500, "getStepCountNow"), count(504, "getStandCountNow"))
    }.getOrNull()
}

private data class WeatherInfo(
    val temp: String,
    val condition: String,
    val city: String,
    val highLow: String?,
    val sunriseMin: Int?,
    val sunsetMin: Int?,
    val humidity: String?,
    val aqi: String?,
    val feelsLike: String?,
    val wind: String?,
    val iconId: Int?,
    val windIconId: Int?
)

private data class HealthInfo(val steps: Int?, val stand: Int?)

private data class Snapshot(
    val weather: WeatherInfo?,
    val battery: Int?,
    val device: String?,
    val alarm: String?,
    val health: HealthInfo?
)
