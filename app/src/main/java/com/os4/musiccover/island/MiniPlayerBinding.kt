package com.os4.musiccover.island

import com.os4.musiccover.AlbumArt
import com.os4.musiccover.Main
import com.os4.musiccover.lyrics.TrackIdentity
import com.os4.musiccover.Xp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.WeakHashMap
import org.json.JSONObject

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1), and given only
 * what it uses of the controller (M2, MiniPlayerParts.kt). See the section comments below for
 * what it is.
 */
internal class MiniPlayerBinding(
    private val views: RowViews,
    private val rowState: RowState,
    private val edits: RowEdits,
    private val rowHost: RowHost,
    private val flightState: FlightState,
    // Made before this part: the islands it colours, the session it reads, the cover it opens.
    private val swapMotion: SwapMotion,
    private val noteMorphMotion: NoteMorphMotion,
    private val shortcutDiscs: ShortcutDiscs,
    private val sessionSource: SessionSource,
    private val cardExchange: CardExchange,
) {

    /** What the islands' material is keyed by; moved here from the controller, its only reader (M2). */
    private fun materialKey(): String {
        MiniPlayerRuntime.islandGeneration()
        return "${if (views.notificationMaterial()) "notification" else "media"}:${MiniPlayerRuntime.materialStyleKey}"
    }

    /**
     * The words' colour, as SystemUI colours its own media card's (IslandMaterial.ink, after
     * MiuiMediaViewControllerImpl.updateForegroundColors): white on the glass and in the full
     * AOD, the light or dark frost's own otherwise. [title] the card title's, else the action
     * buttons'. Opaque: the pill's own alphas say how strong each line is. The same state the
     * islands' material is chosen by, so the two cannot disagree (the card title, a row title
     * and a fallback of our own used to, #87 and #91).
     */
    private fun ink(title: Boolean = true): Int {
        val ctx = Main.sAppCtx ?: views.context
        val color = IslandMaterial.ink(ctx)[if (title) 0 else 1]
        // The AOD darkens the plate under the light frost, and the frost's near black, kept on
        // it, could not be read on the lock screen (2026-10-24). Toward white as deep as the
        // plate has gone, so the words turn with it rather than at once.
        val dim = MiniPlayerRuntime.aodDimFraction
        val base = color or 0xFF000000.toInt()
        return if (dim <= 0f) base
        else androidx.core.graphics.ColorUtils.blendARGB(base, android.graphics.Color.WHITE, dim)
    }

    /** What applyInk last tinted the buttons with; null while they are the OEM's own. */
    private var buttonInk: Int? = null

    // For `op mini` (ink=): how often the ink, the buttons' tint and their layer changed.
    // Counts since the controller was made.
    private var lastInk = 0
    private var inkChanges = 0
    private var buttonInkChanges = 0
    private var buttonLayerWrites = 0

    internal fun describeInkCounts(): String =
        "inkChanges=$inkChanges buttonInk=${buttonInk?.let(Integer::toHexString)} " +
            "buttonChanges=$buttonInkChanges layerWrites=$buttonLayerWrites " +
            "buttons=${views.left.javaClass.simpleName}/${views.right.javaClass.simpleName}"

    fun applyInk() {
        val color = ink()
        if (color != lastInk) {
            lastInk = color
            inkChanges++
        }
        views.player?.setInk(color)
        views.smallIsland?.setInk(color)
        swapMotion.ghostPill?.setInk(color)
        swapMotion.ghostDisc?.setInk(color)
        flightState.flight?.setInk(color)
        for (v in views.spareViews) v.setInk(color)
        // The torch and the camera are white glyphs; on discs of the light frost they vanished.
        // Tinted as the card's own buttons are, only while the discs are under them, and given
        // back as they were after.
        val want = ink(title = false).takeIf {
            shortcutDiscs.discsWanted && shortcutDiscs.discs.any { d -> d?.visibility == View.VISIBLE } && it != android.graphics.Color.WHITE
        }
        if (want == buttonInk) return
        buttonInk = want
        buttonInkChanges++
        for (side in 0..1) tintButton(shortcutDiscs.button(side), want)
    }

    private fun tintButton(v: View, color: Int?) {
        val filter = color?.let { android.graphics.PorterDuffColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        if (v is ImageView) { v.colorFilter = filter; return }
        buttonLayerWrites++
        if (filter == null) v.setLayerType(View.LAYER_TYPE_NONE, null)
        else v.setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply { colorFilter = filter })
    }

    /** The session and track the music island is showing; a new one starts its artwork afresh. */
    internal fun trackMusic(current: MediaController) {
        if (MiniPlayerRuntime.observeSession(current.sessionToken, current.packageName)) {
            edits.cachedCover = null
            // Another player: its artwork, not the last one's.
            thumbShown = null
            edits.lastTrack = ""
        }
        val metadata = sessionSource.metadataOf(current)
        // The song, not the title: Salt, 汽水, QQ and NetEase sing into TITLE, and every line
        // threw the artwork away as a track change (#47, #56).
        val track = TrackIdentity.of(current.packageName, metadata).songKey().ifEmpty {
            current.packageName + "|" + metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        }
        if (track != rowState.lastTrack) {
            if (rowState.lastTrack.isNotEmpty()) {
                Xp.log("MCMini: track changed; preserving dynamic choice=" +
                    if (MiniPlayerRuntime.nativeRequested(current.sessionToken)) "native" else "mini")
            }
            edits.lastTrack = track
            edits.cachedCover = null
        }
    }

    /** The music island's artwork, as far as it has been fetched and scaled. */
    private fun musicCover(metadata: MediaMetadata?): Bitmap? {
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: rowState.cachedCover?.takeUnless { it.isRecycled }
            ?: (if (!rowState.nativeSuppressionRequested) AlbumArt.cardThumbnail() else null)
        if (cover != null && !cover.isRecycled) edits.cachedCover = cover
        return cover
    }

    fun bindMusic(view: MiniPlayerView, current: MediaController, config: JSONObject) {
        view.setArtworkBare(false)
        view.setToggleFace(null)
        view.setSecondFace(null)
        view.skippable = true
        showTimer(view, null)
        showFocusAnim(view, null, null)
        val metadata = sessionSource.metadataOf(current)
        val shown = traced("MC b.thumb") { thumbnailFor(musicCover(metadata), view) }
        view.setToggleShown(true)
        // A player singing into TITLE: the song and singer it spells into ARTIST instead, when
        // they can be read out (TrackIdentity). A title that is still the line being sung is cut,
        // never scrolled (MiniPlayerView.titleScrolls).
        val identity = TrackIdentity.of(current.packageName, metadata)
        val sung = identity.titleIsLine()
        val readOut = sung && identity.songIsRead()
        view.titleScrolls = !sung || readOut
        val title = if (readOut) identity.song() else metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = if (readOut) identity.singer() else metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        view.bind(
            title.orEmpty().ifBlank { "正在播放" },
            artist.orEmpty()
                .ifBlank { metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty() },
            shown,
            sessionSource.stateOf(current)?.state == PlaybackState.STATE_PLAYING,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, views.loader, views.notificationMaterial()) },
            rowHost::togglePlayback,
            { rowHost.skip(next = false) },
            { rowHost.skip(next = true) },
            {
                // Up undoes down: out of the cover or lyrics by a swipe, back into it by one.
                if (MiniPlayerRuntime.takeRestoreScene()) Main.miniPlayerEnterCover()
                else MiniPlayerRuntime.selectNative(current.sessionToken)
            },
            { cardExchange.openCover() },
        )
    }

    /**
     * A notification in the pill: its picture, title and newest line, no play button. A tap
     * puts it back in the stack as the OEM's own row for now; the morph into that row is to
     * come.
     */
    fun bindNote(view: MiniPlayerView, note: LockIslands.Note, config: JSONObject) {
        // A focus notification shown in full is its template: its picture bare, as its row has
        // it, and its main button where the music's play button is.
        val template = note.focus && !note.redacted
        // Every notification's picture at the small island's size, cut to its circle or whole
        // as LockIslands.roundIcon left it (2026-09-26): a plain one was the cover's rounded
        // square, bigger, on a grey plate.
        view.setArtworkBare(true)
        view.skippable = false
        view.titleScrolls = true
        // Its buttons as its row has them: the last - its main one - in the play button's place,
        // the one before it beside it while the pill is the big island (setSecondShown).
        val buttons = if (template) note.buttons else emptyList()
        val primary = buttons.lastOrNull()
        val second = buttons.getOrNull(buttons.size - 2)
        view.setToggleShown(primary != null)
        view.setToggleFace(primary?.let { buttonFace(it, note.timer) })
        view.setSecondFace(second?.let { buttonFace(it, note.timer) })
        // The template's own text, spans and all: a focus protocol writes colour into it and the
        // row shows it coloured, so flattening it here showed the pill the markup instead
        // (the user, 2026-09-28).
        view.bind(
            note.timer?.displayText() ?: note.title.takeUnless { it.isBlank() } ?: appLabel(note.pkg),
            note.text,
            noteBitmap(note),
            false,
            config,
            materialKey(),
            { target -> MiniPlayerRuntime.material(target, views.loader, views.notificationMaterial()) },
            {},
            {},
            {},
            {},
            { noteMorphMotion.expandNote(note.key) },
        )
        showTimer(view, note.timer)
        showFocusAnim(view, note.anim, note.live)
    }

    /**
     * Each pill's own player of a focus template's Lottie, and which picture it plays. The row's
     * Lottie is the plugin's own copy of the library, obfuscated, its composition out of reach:
     * the same file is loaded again with SystemUI's copy - the plugin names it
     * (LottieResUtils.getLottieRes) and its resources hold it - into a LottieAnimationView of
     * SystemUI's that is never attached, whose drawable the pill's artwork shows and runs.
     */
    val focusLotties = java.util.WeakHashMap<Any, Pair<String, android.widget.ImageView>>()

    private fun showFocusAnim(view: MiniPlayerView, anim: LockIslands.Anim?, live: LockIslands.Live?) {
        val d = focusLottie(view, anim)
        if (d == null) view.clearArtworkDrawable() else view.showArtworkDrawable(d)
        view.showArtworkView(liveArt(view, live))
    }

    /**
     * Each place's own live picture ([LockIslands.Live]) and which one it is: a view has one
     * parent, so the pill, the small island and a flight each make their own.
     */
    val liveArts = java.util.WeakHashMap<Any, Pair<String, View>>()
    private val liveFailed = HashSet<String>()

    /**
     * Live pictures no place shows any more, by id, for the next place that asks for the same.
     * Each one made is the plugin's own view, and the torch's inflates three bitmaps on the main
     * thread: made afresh for every place that took the torch - a switch's stand-ins, a flight,
     * a pill that had been the music in between - it was 7-18ms of the first frame of every
     * switch and every pull (perfetto 2026-09-29, `decodeBitmap` under FlashLightView inflate).
     */
    private val liveSpares = HashMap<String, ArrayDeque<View>>()

    private fun spareLive(held: Pair<String, View>) {
        // Not a clip: it is set playing once, when it is made, and put back up after its surface
        // went it is not known to play again.
        if (held.first.startsWith(LockIslands.Live.VIDEO + "#")) return
        val spares = liveSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /** A spare of [id] that has left its last place: one still in it would be taken from under it. */
    private fun takeSpareLive(id: String): View? {
        val spares = liveSpares[id] ?: return null
        val it = spares.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (v.parent == null) {
                it.remove()
                return v
            }
        }
        return null
    }

    /** [owner]'s view of [live], made on first asking; null for none, or one that would not make. */
    fun liveArt(owner: Any, live: LockIslands.Live?): View? {
        val held = liveArts[owner]
        if (live == null) {
            liveArts.remove(owner)?.let(::spareLive)
            return null
        }
        if (held != null) {
            if (held.first == live.id) return held.second
            liveArts.remove(owner)
            spareLive(held)
        }
        if (live.id in liveFailed) return null
        takeSpareLive(live.id)?.let { spare ->
            liveArts[owner] = live.id to spare
            return spare
        }
        val view = runCatching { makeLiveArt(live) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            liveFailed += live.id
            lottieNote("live ${live.id} failed: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}", null)
        }.getOrNull() ?: return null
        liveArts[owner] = live.id to view
        lottieNote("live ${live.id} made", null)
        return view
    }

    /**
     * The plugin's own view for [live], set up as the row sets its own
     * (ModuleNewImageTextViewHolder.showEffects, IslandIconViewHolder.setMP4Icon), in a host that
     * draws it at the size the row's layout gives it.
     */
    private fun makeLiveArt(live: LockIslands.Live): View? {
        val plugin = LockIslands.pluginOf(live.row.get()) ?: return null
        val ctx = plugin.ctx
        fun dimen(name: String): Int {
            val id = ctx.resources.getIdentifier(name, "dimen", "miui.systemui.plugin")
            return if (id == 0) 0 else ctx.resources.getDimensionPixelSize(id)
        }
        return when (live.kind) {
            LockIslands.Live.FLASH -> {
                val v = plugin.loader.loadClass("com.mi.widget.view.FlashLightView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                // Its row's layout lets its glow - taller than its square - out (clipChildren
                // false); made in code, a FrameLayout clips it.
                (v as? ViewGroup)?.clipChildren = false
                (Xp.callMethod(v, "getFlashLightOffset") as? FloatArray)?.let { o ->
                    o[0] = 0f; o[1] = 0.14f; o[2] = -50f; o[3] = 0f
                }
                LiveArtHost(views.context, v, dimen("focus_notify_profile_container_size"))
            }
            LockIslands.Live.VIDEO -> {
                val v = plugin.loader.loadClass("miui.systemui.widget.TextureVideoView")
                    .getConstructor(android.content.Context::class.java).newInstance(ctx) as View
                val uri = plugin.call("miui.systemui.util.VideoResUtils", "getVideoRes", live.src, ctx) as String
                Xp.callMethod(v, "setOnPreparedListener", android.media.MediaPlayer.OnPreparedListener { mp ->
                    // Silent on the lock screen whatever the clip carries.
                    runCatching { mp.setVolume(0f, 0f) }
                    mp.isLooping = live.loop
                    Xp.callMethod(v, "start")
                })
                Xp.callMethod(v, "setOnErrorListener", android.media.MediaPlayer.OnErrorListener { _, what, extra ->
                    lottieNote("live ${live.id} error $what/$extra", null)
                    runCatching { Xp.callMethod(v, "stopPlayback") }
                    true
                })
                Xp.callMethod(v, "setVideoURI", android.net.Uri.parse(uri))
                LiveArtHost(views.context, v, 0)
            }
            else -> null
        }
    }

    /**
     * [owner]'s player of [anim] - a pill, or the small island - its drawable to show, playing
     * or held as the template says; null, and [owner]'s player stopped, for none.
     */
    fun focusLottie(owner: Any, anim: LockIslands.Anim?): android.graphics.drawable.Drawable? {
        if (anim == null) {
            focusLotties.remove(owner)?.let(::spareLottie)
            return null
        }
        val id = "${anim.src}#${anim.number}"
        var held = focusLotties[owner]
        if (held?.first != id) {
            held?.let { focusLotties.remove(owner); spareLottie(it) }
            // Another place's, let go: as liveArt's spares, not a player made for every place.
            val lottie = lottieSpares[id]?.removeFirstOrNull() ?: loadFocusLottie(anim)
                ?: return focusLottie(owner, null)
            held = id to lottie
            focusLotties[owner] = held
        }
        val lottie = held.second
        lottieAutoplay[lottie] = anim.autoplay
        runCatching {
            Xp.callMethod(lottie, "setRepeatCount", anim.repeat)
            Xp.callMethod(lottie, "setRepeatMode", 1)
            if (anim.autoplay && !MiniPlayerScene.aodActive) {
                // Held unseen: its place coming back plays it (lottiesFollowSight), not a refresh.
                if (lottie !in lottiesUnseen && Xp.callMethod(lottie, "isAnimating") != true) {
                    lottieNote("asked to resume vis=${lottie.drawable?.isVisible}", lottie)
                    Xp.callMethod(lottie, "resumeAnimation")
                }
            } else Xp.callMethod(lottie, "pauseAnimation")
        }
        return lottie.drawable?.let { d -> lottieWatches.getOrPut(d) { LottieWatch(d, lottie) } }
    }

    /** Which players their template asks to play, for the wake to play them again. */
    private val lottieAutoplay = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * The doze holds every place's Lottie on its frame; the wake plays again those that play.
     * The window stays visible through the AOD, so nothing pauses them on its own, and one that
     * loops redraws the whole NotificationShade every frame: the recorder's voiceWave kept the
     * AOD at ~63fps, RenderThread 57% and main 37% of a core (2026-09-30).
     */
    internal fun lottiesFollowDoze() {
        val doze = MiniPlayerScene.aodActive
        for (held in focusLotties.values) runCatching {
            val lottie = held.second
            if (doze) Xp.callMethod(lottie, "pauseAnimation")
            else if (lottieAutoplay[lottie] == true && lottie !in lottiesUnseen &&
                Xp.callMethod(lottie, "isAnimating") != true)
                Xp.callMethod(lottie, "resumeAnimation")
        }
    }

    /** Players held because their place could not be seen, for the place coming back to play. */
    private val lottiesUnseen = java.util.WeakHashMap<android.widget.ImageView, Boolean>()

    /**
     * A lit lock screen holds every place's Lottie that nobody can see; the place coming back
     * plays it again. A Lottie drawable pauses itself when its ImageView goes GONE or its window
     * hides (setVisible), but not when a place is faded out - alpha or transitionAlpha 0 on it
     * or an ancestor, which is how most places here are put away - and one that loops redraws
     * the whole NotificationShade every frame, seen or not: a looping one (the clock's island)
     * kept a still lock screen at ~95fps, main and RenderThread 68% of a core (2026-10-01). Asked
     * before every frame: an unseen player's own tick is what schedules the frame that holds it,
     * and the frame a place comes back in is the one that plays it again.
     */
    internal fun lottiesFollowSight() {
        if (focusLotties.isEmpty() || MiniPlayerScene.aodActive) return
        for ((owner, held) in focusLotties) runCatching {
            val lottie = held.second
            if (lottieAutoplay[lottie] != true) return@runCatching
            val seen = (owner as? View)?.let(::onScreen) ?: true
            val playing = Xp.callMethod(lottie, "isAnimating") == true
            if (playing && !seen) {
                Xp.callMethod(lottie, "pauseAnimation")
                lottiesUnseen[lottie] = true
                lottieNote("unseen, held", lottie)
            } else if (seen && lottiesUnseen.remove(lottie) != null && !playing) {
                Xp.callMethod(lottie, "resumeAnimation")
                lottieNote("seen, played", lottie)
            }
        }
    }

    /** Drawn and not faded out: attached to a showing window, shown, and no alpha 0 up the chain. */
    private fun onScreen(v: View): Boolean {
        if (!v.isAttachedToWindow || v.windowVisibility != View.VISIBLE || !v.isShown) return false
        var p: View? = v
        while (p != null) {
            if (p.alpha <= 0f || p.transitionAlpha <= 0f) return false
            p = p.parent as? View
        }
        return true
    }

    /** Players no place shows any more, by picture, for the next place that plays the same. */
    private val lottieSpares = HashMap<String, ArrayDeque<android.widget.ImageView>>()

    private fun spareLottie(held: Pair<String, android.widget.ImageView>) {
        runCatching { Xp.callMethod(held.second, "pauseAnimation") }
        lottiesUnseen.remove(held.second)
        val spares = lottieSpares.getOrPut(held.first) { ArrayDeque() }
        if (spares.none { it === held.second } && spares.size < LIVE_SPARES) spares.addLast(held.second)
    }

    /**
     * Compositions by the plugin's raw resource, shared by every pill that plays one; the
     * pictures that would not load, not tried again on every refresh (a failure was retried
     * twenty times in a minute, 2026-09-25).
     */
    private val focusCompositions = HashMap<Int, Any>()
    val focusLottieFailed = HashSet<String>()

    private fun loadFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        if (anim.src in focusLottieFailed) return null
        return runCatching { makeFocusLottie(anim) }.onFailure {
            // Thrown: this build has no way to it, not worth trying on every refresh.
            focusLottieFailed += anim.src
            focusLottieWhy = "failed ${anim.src}: ${(it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it}"
            lottieNote(focusLottieWhy, null)
        }.getOrNull()
    }

    /** Why the last try gave nothing, said once until it changes. */
    var focusLottieWhy = ""

    private fun lottieMiss(why: String): android.widget.ImageView? {
        if (why != focusLottieWhy) {
            focusLottieWhy = why
            lottieNote("none: $why", null)
        }
        return null
    }

    /**
     * The plugin as a package of its own, code and resources: its raw Lottie files are its
     * resources, not SystemUI's, and a view of its in the row may carry SystemUI's.
     */
    private val pluginContext: android.content.Context? by lazy {
        runCatching {
            views.context.createPackageContext("miui.systemui.plugin",
                android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { MiniPlayerRuntime.noteTouch("focus lottie: no plugin package: $it") }.getOrNull()
    }

    /** The last load's own exception, when it gave no composition. */
    private var lottieLoadError: Any? = null

    /**
     * The plugin's raw Lottie [res] as SystemUI's copy of the library reads it: its
     * fromRawResSync(Context, String cacheKey, int), arguments reordered by R8 and matched by
     * type; its LottieResult's getters gone, its public fields read. One per file, shared.
     */
    private fun compositionFor(res: Int, plugin: android.content.Context): Any? {
        focusCompositions[res]?.let { return it }
        val systemui = views.host.javaClass.classLoader ?: return null
        val factory = systemui.loadClass("com.airbnb.lottie.LottieCompositionFactory")
        val load = factory.methods.first { m ->
            m.name == "fromRawResSync" && m.parameterTypes.any { it == Int::class.javaPrimitiveType } &&
                m.parameterTypes.any { it == android.content.Context::class.java }
        }
        val args = load.parameterTypes.map { t ->
            when (t) {
                android.content.Context::class.java -> plugin
                Int::class.javaPrimitiveType -> res
                String::class.java -> "musiccover_focus_$res"
                else -> null
            }
        }.toTypedArray()
        val result = load.invoke(null, *args)
        lottieLoadError = runCatching { Xp.getObjectField(result, "exception") }.getOrNull()
        return Xp.getObjectField(result, "value")?.also { focusCompositions[res] = it }
    }

    /** A LottieAnimationView of SystemUI's, never attached, holding [composition]. */
    private fun newLottie(composition: Any, plugin: android.content.Context, systemui: ClassLoader): android.widget.ImageView {
        val view = systemui.loadClass("com.airbnb.lottie.LottieAnimationView")
            .getConstructor(android.content.Context::class.java).newInstance(plugin) as android.widget.ImageView
        Xp.callMethod(view, "setComposition", composition)
        return view
    }

    private fun lottieView(res: Int, plugin: android.content.Context): android.widget.ImageView? {
        val composition = compositionFor(res, plugin) ?: return null
        return newLottie(composition, plugin, views.host.javaClass.classLoader ?: return null)
    }

    private fun makeFocusLottie(anim: LockIslands.Anim): android.widget.ImageView? {
        // The plugin as the row has it - its view's context and classes, which loaded the file
        // (2026-09-25) - else the plugin's package; its package context alone never did.
        val source = anim.row.get()?.let { LockIslands.focusIconView(it) }
        val plugin = source?.context ?: pluginContext ?: return lottieMiss("no plugin context")
        val loader = source?.javaClass?.classLoader ?: plugin.classLoader
        val utils = loader.loadClass("miui.systemui.util.LottieResUtils")
        val instance = runCatching { utils.getField("INSTANCE").get(null) }.getOrNull()
        val res = utils.methods.first { it.name == "getLottieRes" && it.parameterTypes.size == 2 }
            .invoke(instance, anim.src, anim.number) as Int
        if (res <= 0) return lottieMiss("no res for ${anim.src}")
        val systemui = views.host.javaClass.classLoader ?: return lottieMiss("no systemui loader")
        val composition = compositionFor(res, plugin)
            ?: return lottieMiss("load ${anim.src}: $lottieLoadError")
        val view = newLottie(composition, plugin, systemui)
        runCatching { utils.getField("IMAGE_ASSETS_FOLDER").get(null) as? String }.getOrNull()
            ?.let { Xp.callMethod(view, "setImageAssetsFolder", it) }
        runCatching {
            Xp.callMethod(view, "addAnimatorListener", object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) = lottieNote("start", view)
                override fun onAnimationEnd(a: android.animation.Animator) = lottieNote("end", view)
                override fun onAnimationCancel(a: android.animation.Animator) = lottieNote("cancel", view)
                override fun onAnimationPause(a: android.animation.Animator) = lottieNote("pause", view)
                override fun onAnimationResume(a: android.animation.Animator) = lottieNote("resume", view)
            })
        }
        focusLottieWhy = ""
        lottieNote("loaded ${anim.src} res=$res", view)
        return view
    }

    private val noteBitmaps = HashMap<String, Triple<Long, android.graphics.drawable.Drawable?, Bitmap?>>()

    /** For `op mini`: what the focus Lotties did, and who hid them - kept apart from the touches. */
    val lottieLog = ArrayDeque<String>()

    private fun lottieNote(what: String, lottie: android.widget.ImageView?) {
        val p = lottie?.let { runCatching { Xp.callMethod(it, "getProgress") }.getOrNull() }
        synchronized(lottieLog) {
            lottieLog.addLast("${android.os.SystemClock.uptimeMillis() % 100000} $what p=$p")
            while (lottieLog.size > 24) lottieLog.removeFirst()
        }
    }

    /**
     * The Lottie drawable as the pill's artwork shows it: passed straight through, but its
     * visibility changes - the only thing that pauses a Lottie drawable on its own - are noted.
     */
    inner class LottieWatch(inner: android.graphics.drawable.Drawable, val lottie: android.widget.ImageView) :
        android.graphics.drawable.DrawableWrapper(inner) {
        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            if (visible != isVisible) lottieNote("visible=$visible", lottie)
            return super.setVisible(visible, restart)
        }
    }

    val lottieWatches = java.util.WeakHashMap<android.graphics.drawable.Drawable, LottieWatch>()

    /**
     * A focus button as the pill draws it, by the plugin's own rule (ModuleButtonViewHolder
     * .setActionNormalData / setActionTextData), the same for every app: its picture the
     * plugin's own for its name when it has one (ACTIONS_RESOURCE_MAP), or its Lottie held at
     * its first frame (LOTTIE_RES_MAP_NEXT, updateAndPauseAnimation), else the app's picture;
     * its plate the plugin's focus_button_background_n in its colour, a pressed one under it.
     */
    private fun buttonFace(b: LockIslands.Button, timer: LockIslands.Timer?) = ActionFace(
        key = "${b.type}|${b.iconName}|${b.bg}|${b.press}|${b.label}|${b.titleColor}|" +
            "${b.progress?.let { "${it.value}/${it.auto}/${it.ccw}/${it.color}/${it.colorEnd}" }}",
        icon = {
            when (b.type) {
                // setActionTextData: its title, in its title colour, on its plate.
                2 -> TextFaceDrawable(b.label?.toString().orEmpty(), b.titleColor ?: android.graphics.Color.WHITE,
                    13f * views.context.resources.displayMetrics.scaledDensity)
                // setActionProgressData: its picture inside its ring.
                1 -> progressFace(b, timer)
                else -> buttonIcon(b)
            }
        },
        // A plate only under words: the plugin's pictures carry their own round shell, and
        // only setActionTextData gives its button a background - one under a picture drew a
        // second shell round it (2026-09-25).
        background = { if (b.type == 2) buttonPlate(b) else null },
        label = b.label,
        onClick = { pressButton(b) },
        wide = b.type == 2,
    )

    /**
     * A progress button: its picture inset in its ring - the ring at its value, or at its
     * timer's share of the timer's total for an auto one (NotificationTimeKeeper.getProgresses),
     * redrawn on each tick of the timer.
     */
    private fun progressFace(b: LockIslands.Button, timer: LockIslands.Timer?): android.graphics.drawable.Drawable {
        val spec = b.progress
        val auto = spec?.auto == true && timer != null && timer.totalMs > 0L
        val ring = ProgressRingDrawable(
            stroke = views.dp(2.5f).toFloat(),
            color = spec?.color ?: android.graphics.Color.WHITE,
            colorEnd = spec?.colorEnd,
            ccw = spec?.ccw == true,
            progress = if (auto) ({ timer.progress() })
                else ({ (spec?.value ?: 0).toFloat() }),
        )
        if (auto) autoRings[ring] = true
        val icon = buttonIcon(b)
        val layers = listOfNotNull(icon?.let { android.graphics.drawable.InsetDrawable(it, 0.24f) }, ring)
        return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    /** The plugin's context and classes as the row has them, else its package's. */
    private fun pluginOf(row: View?): Pair<android.content.Context, ClassLoader>? {
        LockIslands.pluginOf(row)?.let { return it.ctx to it.loader }
        val ctx = pluginContext ?: return null
        return ctx to ctx.classLoader
    }

    /** The plugin's name-to-picture maps for its buttons: its stills and its Lotties. */
    private var buttonMapsCache: Pair<Map<*, *>, Map<*, *>>? = null

    private fun buttonMaps(loader: ClassLoader): Pair<Map<*, *>, Map<*, *>>? = buttonMapsCache ?: runCatching {
        val holder = loader.loadClass("miui.systemui.notification.focus.moduleV3.ModuleButtonViewHolder")
        fun map(name: String) = holder.getDeclaredField(name).apply { isAccessible = true }.get(null) as Map<*, *>
        (map("ACTIONS_RESOURCE_MAP") to map("LOTTIE_RES_MAP_NEXT")).also { buttonMapsCache = it }
    }.onFailure { lottieNote("button maps: $it", null) }.getOrNull()

    private fun buttonIcon(b: LockIslands.Button): android.graphics.drawable.Drawable? {
        val name = b.iconName
        val plugin = pluginOf(b.row.get())
        if (name != null && plugin != null) {
            val maps = buttonMaps(plugin.second)
            // The Lottie first, as the plugin does (shouldAnimLottie, in setActionNormalData and
            // setActionProgressData alike): a name in both maps is drawn as its Lottie's first
            // frame. The still first drew the countdown's action_restart_timer as
            // restart_timer_v3 where the row has pause_island_blue (2026-09-30).
            (maps?.second?.get(name) as? Int)?.takeIf { it > 0 }?.let { res ->
                runCatching { lottieView(res, plugin.first) }.getOrNull()?.let { lottie ->
                    runCatching { Xp.callMethod(lottie, "setProgress", 0f) }
                    lottie.drawable?.let { return it }
                }
            }
            (maps?.first?.get(name) as? Int)?.takeIf { it != 0 }?.let { res ->
                runCatching { plugin.first.getDrawable(res) }.getOrNull()?.let { return it }
            }
        }
        return runCatching { (b.picture ?: b.action?.getIcon())?.loadDrawable(views.context) }.getOrNull()
    }

    private fun buttonPlate(b: LockIslands.Button): android.graphics.drawable.Drawable {
        val ctx = pluginOf(b.row.get())?.first
        fun plate(name: String, fallback: Int): android.graphics.drawable.Drawable {
            val id = ctx?.resources?.getIdentifier(name, "drawable", "miui.systemui.plugin") ?: 0
            return (if (id != 0) runCatching { ctx!!.getDrawable(id)?.mutate() }.getOrNull() else null)
                ?: android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(fallback)
                }
        }
        val normal = plate("focus_button_background_n", 0x33FFFFFF)
        val pressed = plate("focus_button_background_p", 0x55FFFFFF)
        b.bg?.let { (normal as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        // Pressed: its own colour, else its plate's a little lighter, as the dark template's.
        (b.press ?: b.bg?.let { androidx.core.graphics.ColorUtils.blendARGB(it, android.graphics.Color.WHITE, 0.15f) })
            ?.let { (pressed as? android.graphics.drawable.GradientDrawable)?.setColor(it) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(IntArray(0), normal)
        }
    }

    /**
     * A tap: the row's own button is pressed - the plugin then does what it does for every app,
     * the keyguard asked to go for an activity, the state flipped, the notification collapsed
     * when asked. With no row to press, its intent is sent as the plugin would build it.
     */
    private fun pressButton(b: LockIslands.Button) {
        MiniPlayerRuntime.noteTouch("focus button ${b.index} '${b.label}' ${b.iconName}")
        val target = b.row.get()?.let { rowButton(it, b.index, b.type) }
        if (target != null && target.hasOnClickListeners()) {
            target.performClick()
            return
        }
        runCatching {
            val intent = b.action?.actionIntent ?: b.intentUri?.let { uri ->
                val i = android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
                val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                when (b.intentType) {
                    2 -> android.app.PendingIntent.getBroadcast(views.context, b.postTime.toInt(), i, flags)
                    3 -> android.app.PendingIntent.getService(views.context, b.postTime.toInt(), i, flags)
                    else -> android.app.PendingIntent.getActivity(views.context, b.postTime.toInt(), i, flags)
                }
            }
            intent?.send()
        }.onFailure { MiniPlayerRuntime.noteTouch("focus button failed: $it") }
    }

    /**
     * The row's own button: focus_button_icon1, 2, 3 for a picture, focus_button_progress1, 2, 3
     * for one in a ring, focus_button_container_action for the text one (ModuleButtonViewHolder).
     */
    private fun rowButton(row: View, index: Int, type: Int): View? {
        val names = when (type) {
            1 -> setOf("focus_button_progress${index + 1}")
            2 -> setOf("focus_button_container_action")
            else -> setOf("focus_button_icon${index + 1}")
        }
        val queue = ArrayDeque<View>()
        queue.add(row)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.id != View.NO_ID && v.visibility == View.VISIBLE) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name in names) return v
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
        }
        return null
    }

    /**
     * Islands whose title is a focus template's timer - a stopwatch, a countdown - and the timer
     * each shows: the row's chronometer ticks, so the island's line does too, on the second as
     * the chronometer turns it, in tabular figures as HyperChronometer draws them ("tnum").
     */
    private val timerViews = java.util.WeakHashMap<MiniPlayerView, LockIslands.Timer>()

    private fun showTimer(view: MiniPlayerView, timer: LockIslands.Timer?) {
        if (timer == null) {
            rollers.remove(view)?.clear()
            if (timerViews.remove(view) != null) view.titleView.fontFeatureSettings = null
            return
        }
        // Bound again to the timer it already shows (every refresh): the line is set again over
        // what bind wrote, but a tick still coming goes on where it was rather than starting
        // over. One that stopped - every view it had detached - is started again. PR #15.
        val same = timerViews[view] === timer && views.handler.hasCallbacks(timerTick)
        timerViews[view] = timer
        if (view.titleView.fontFeatureSettings != "tnum") view.titleView.fontFeatureSettings = "tnum"
        timerText(view, timer, timer.displayText())
        if (same) return
        views.handler.removeCallbacks(timerTick)
        timerTick.run()
    }

    /** The pills whose timer line turns its digits: a running countdown's (timerType -1). */
    private val rollers = java.util.WeakHashMap<MiniPlayerView, RollingDigits>()

    private fun timerText(view: MiniPlayerView, timer: LockIslands.Timer, text: String) {
        if (timer.type == -1) {
            rollers.getOrPut(view) { RollingDigits(view.titleView) }.set(text)
        } else {
            rollers.remove(view)?.clear()
            if (view.titleView.text?.toString() != text) view.titleView.text = text
        }
    }

    /** Progress buttons run by their template's timer: redrawn on every tick. */
    private val autoRings = java.util.WeakHashMap<ProgressRingDrawable, Boolean>()

    private val timerTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var next = Long.MAX_VALUE
            for ((view, timer) in timerViews.entries.toList()) {
                if (!view.isAttachedToWindow || !timer.running) continue
                timerText(view, timer, timer.text(now))
                // To the chronometer's next second: up from its start, down to its end.
                val ms = timer.elapsedMs(now)
                val wait = if (timer.type > 0) 1000L - ms % 1000L else (ms % 1000L).takeIf { it > 0L } ?: 1000L
                next = minOf(next, wait)
            }
            autoRings.keys.toList().forEach { it.invalidateSelf() }
            if (next != Long.MAX_VALUE) views.handler.postDelayed(this, next + 5L)
        }
    }

    /** A note's picture as the pill's artwork wants it: a bitmap, drawn once per update. */
    private fun noteBitmap(note: LockIslands.Note): Bitmap? {
        // The picture too: the stack island's changes with its apps while its newest stays.
        noteBitmaps[note.key]?.let { (time, icon, bitmap) -> if (time == note.time && icon === note.icon) return bitmap }
        val drawable = note.icon
        val side = views.dp(56f)
        val bitmap = drawable?.let {
            runCatching {
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { b ->
                    val canvas = android.graphics.Canvas(b)
                    // Fitted, not stretched: a picture left whole may not be square.
                    val w = it.intrinsicWidth
                    val h = it.intrinsicHeight
                    val old = it.copyBounds()
                    if (w > 0 && h > 0 && w != h) {
                        val k = side.toFloat() / maxOf(w, h)
                        val dw = (w * k).toInt()
                        val dh = (h * k).toInt()
                        it.setBounds((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2)
                    } else {
                        it.setBounds(0, 0, side, side)
                    }
                    it.draw(canvas)
                    it.bounds = old
                }
            }.getOrNull()
        }
        if (noteBitmaps.size > 60) noteBitmaps.clear()
        noteBitmaps[note.key] = Triple(note.time, note.icon, bitmap)
        return bitmap
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = views.context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    var thumb: Bitmap? = null
    /** The artwork last handed to the pill; it stays until a new one is ready to replace it. */
    var thumbShown: Bitmap? = null

    /**
     * The artwork at the size the pill draws it. A player's album art is often 1000px or more,
     * and handing that straight to the ImageView uploaded the whole thing to the GPU on the
     * frame of every track change. The scaling runs off the main thread; until it lands the
     * pill keeps the artwork it had.
     */
    private fun thumbnailFor(cover: Bitmap?, view: MiniPlayerView): Bitmap? {
        // A skip sends the new title before the new artwork: keep the old one until it comes
        // rather than going blank for those frames - the flash under the spin.
        if (cover == null || cover.isRecycled) return shown(null)
        // By content, not by object: MediaController.getMetadata() is a binder call and hands
        // back a new Bitmap every time. Compared by identity, every refresh was "new artwork",
        // scaled it again, and the scaled one's own refresh fetched another - a loop that never
        // let a scaled one be shown, which left the pill's artwork empty.
        val key = artworkKey(cover)
        if (key == thumbKey) return shown(thumb)
        // One thumbnail for every view the music is bound to, so sized by a laid-out artwork -
        // the pill's, else this one's - never less than the pill's default. A flight or spare
        // bound before its first layout has an artwork 1px wide: the thumbnail came out 2x2,
        // was kept for the track, and the pill drew it as a blurred blob (filmed 2026-09-25).
        val minSide = views.dp(40f)
        val side = (listOf(views.player?.artworkView?.width ?: 0, view.artworkView.width)
            .firstOrNull { it >= minSide } ?: views.dp(48f)) * 2
        thumbKey = key
        if (cover.width <= side && cover.height <= side) {
            thumb = cover
            return shown(cover)
        }
        thumb = null
        MiniPlayerRuntime.artWorker.execute {
            val scaled = runCatching {
                val k = side.toFloat() / minOf(cover.width, cover.height)
                Bitmap.createScaledBitmap(cover, (cover.width * k).toInt().coerceAtLeast(1),
                    (cover.height * k).toInt().coerceAtLeast(1), true)
            }.getOrNull()
            views.handler.post {
                if (thumbKey != key || scaled == null) return@post
                thumb = scaled
                // Straight onto the pill: a refresh here fetched the metadata again for nothing.
                val ready = shown(scaled)
                if (rowState.selectedIsland == MUSIC_ISLAND) views.player?.showArtwork(ready)
                else if (rowState.smallKey == MUSIC_ISLAND) rowHost.refresh()
            }
        }
        return shown(null)
    }

    private var thumbKey: String? = null

    /** Size and an 8x8 sample of the pixels: cheap, and different whenever the picture is. */
    private fun artworkKey(b: Bitmap): String {
        var hash = 17L
        if (b.config != Bitmap.Config.HARDWARE) {
            runCatching {
                for (j in 0 until 8) for (i in 0 until 8) {
                    hash = hash * 31 + b.getPixel(i * (b.width - 1) / 7, j * (b.height - 1) / 7)
                }
            }
        } else hash = b.generationId.toLong()
        return "${b.width}x${b.height}#$hash"
    }

    /**
     * A skip sends several metadata updates, one of them with no artwork, while the new
     * artwork is still being scaled: each of those used to blank the pill's artwork for a
     * frame - the grey flash, twice per skip on film. Nothing replaces the shown artwork but
     * a ready one.
     */
    private fun shown(ready: Bitmap?): Bitmap? {
        if (ready != null && !ready.isRecycled) thumbShown = ready
        return thumbShown?.takeUnless { it.isRecycled }
    }
}
