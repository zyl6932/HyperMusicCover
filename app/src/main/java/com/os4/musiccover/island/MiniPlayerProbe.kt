package com.os4.musiccover.island

import com.os4.musiccover.Xp
import android.view.View

/**
 * Moved out of MiniPlayerController as it was (MiniPlayerController plan, M1); `c` is the
 * controller. See the section comments below for what it is.
 */
internal class MiniPlayerProbe(private val c: MiniPlayerController) {

    /** What keeps the row from being quiet, for `op mini`'s gone lines; "quiet" when nothing does. */
    fun quietWhy(): String = buildList {
        if (c.swapMotion.swap != null) add("swap")
        if (c.morph != null) add("morph")
        if (c.groupMotion.group != null) add("group")
        if (c.flightState.flight != null) add("flight")
        if (c.cardExchange.exchange != null) add("exchange")
        c.flightState.noteMorphKey?.let { add("noteMorph=${it.takeLast(6)}") }
        if (c.flightState.pillLandingBox != null) add("landing")
        if (c.rowHeldOff) add("heldOff")
        if (c.followRowFade < 0.99f) add("fade=${"%.2f".format(c.followRowFade)}")
        if (MiniPlayerScene.aodActive) add("aod")
        if (c.player?.visibility != View.VISIBLE) add("vis=${c.player?.visibility}")
    }.joinToString(",").ifEmpty { "quiet" }
    fun rowTree(match: String): String {
        val stack = c.stackHost.notificationStack() ?: return "no stack"
        val sb = StringBuilder()
        fun rows(v: View): List<View> = listOf(v) + c.stackHost.childRows(v)
        for (i in 0 until stack.childCount) {
            val child = stack.getChildAt(i)
            if (!child.javaClass.name.contains("ExpandableNotificationRow")) continue
            for (row in rows(child)) {
                val key = c.stackHost.rowKey(row) ?: continue
                if (match.isNotEmpty() && !key.contains(match)) continue
                val pub = runCatching { Xp.callMethod(row, "isShowingPublic") }.getOrNull()
                val sens = runCatching { Xp.callMethod(row, "getSensitive") }.getOrNull()
                sb.append("ROW ").append(key).append(" public=").append(pub).append(" sensitive=")
                    .append(sens).append(" top=").append(row.translationY.toInt()).appendLine()
                c.stackHost.dumpTree(row, 1, sb)
                if (sb.length > 60000) return sb.append("...cut").toString()
            }
        }
        return if (sb.isEmpty()) "no row matches '$match'" else sb.toString()
    }
    fun describe(): String {
        val native = c.expandedKey()?.let(c::nativeFor)
        val nativeY = native?.let { n -> IntArray(2).also(n::getLocationOnScreen)[1] }
        val sink = " nativeY=$nativeY nativeTy=${native?.translationY} nativeTarget=${native?.let(c.stackHost::stackTargetY)} " +
            "pin=${c.stackHost.pinned?.get() != null} frozen=${c.stackHost.pinnedAt} pinY=${c.stackHost.pinY.value} pinDy=${c.stackHost.pinnedDy} " +
            "stackTy=${c.stackHost.notificationStack()?.translationY} "
        val islands = c.sessionSource.describeSession() + c.spreadMotion.describeSpread() + " rowAnim=${c.rowAnimating} swap=${c.swapMotion.swap != null} noteMorph=${c.flightState.noteMorphKey != null} " +
            "flight=${c.flightState.flight != null} drag=${c.noteDragMotion.noteDrag != null} ${c.player?.touchState()} " +
            "out=${c.expandedKey()?.takeLast(12)} xchg=${c.cardExchange.exchange?.let { x -> "out=${x.expanded?.takeLast(6)} pend=${x.pending?.takeLast(6)} " +
                "seats=${x.seats?.big?.takeLast(6)}/${x.seats?.small?.takeLast(6)} " +
                x.movers.values.joinToString(" ") { c.cardExchange.moverState(it) } }} " +
            "row=${c.islandKeys.size} sel=${c.selectedIsland?.takeLast(24)} " +
            "small=${c.smallKey?.takeLast(24)} smallShown=${c.smallIsland?.visibility == View.VISIBLE} " +
            "follow=${c.followBranch} " + sink
        val v = c.player ?: return islands + "no pill"
        val xy = IntArray(2).also(v::getLocationOnScreen)
        val icons = "pillArt=[${v.artworkState()}] smallIcon=[${c.smallIsland?.iconState()}] " +
            c.binding.focusLotties.entries.joinToString(" ") { (owner, held) ->
                val d = held.second.drawable
                val shownIn = when (owner) {
                    is MiniPlayerView -> owner.artworkDrawable.let { it === d || it === c.binding.lottieWatches[d] }
                    else -> d?.callback != null
                }
                "lottie[${if (owner === v) "pill" else owner.javaClass.simpleName} ${held.first} " +
                    "anim=${runCatching { Xp.callMethod(held.second, "isAnimating") }.getOrNull()} " +
                    "p=${runCatching { Xp.callMethod(held.second, "getProgress") }.getOrNull()} " +
                    "vis=${d?.isVisible} cb=${d?.callback?.javaClass?.simpleName} " +
                    "shown=$shownIn]"
            } + c.binding.liveArts.entries.joinToString(" ", prefix = " ") { (owner, held) ->
                "live[${if (owner === v) "pill" else owner.javaClass.simpleName} ${held.first} " +
                    "attached=${held.second.isAttachedToWindow} shown=${held.second.isShown} " +
                    "${held.second.width}x${held.second.height}]"
            } + " lottieWhy=[${c.binding.focusLottieWhy}] failed=${c.binding.focusLottieFailed} lottieLog=[" +
            synchronized(c.binding.lottieLog) { c.binding.lottieLog.joinToString(" ; ") } + "] "
        val h = c.header?.get()
        return islands + icons + "pill v=${v.visibility} a=${v.alpha} ta=${v.transitionAlpha} at=${xy[0]},${xy[1]} " +
            "${v.width}x${v.height} morph=${c.morph != null} header=" +
            (if (h == null) "none" else "v=${h.visibility} a=${h.alpha} ta=${h.transitionAlpha}") +
            " last=[${c.lastPresentationLog}] || land: " + c.landTrace.joinToString(" ; ")
    }
    /** For `op fod`: the sensor as the row sees it, and where the row is. */
    fun describeFingerprint(): String {
        val fod = c.fingerprintInHost()
        val rest = c.pillRest(c.smallKey != null)
        return "sensor(host)=${fod ?: "none"} row centre=${rest?.centerY} height=${rest?.height}" +
            " lifted=${if (c.fodLiftedTo.isNaN()) "never" else c.fodLiftedTo}" +
            " liftPx=${c.fodLiftPx.toInt()}"
    }
}
