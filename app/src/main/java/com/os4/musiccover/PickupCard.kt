package com.os4.musiccover

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * The pickup island's card: HyperOS's own shape, taken from 小爱同学
 * (`res/layout/memory_scene_focused_remote_view.xml`), which is the card the phone shows when
 * its assistant reads a pickup code off the screen. [R.layout.mc_pickup_card] is that layout,
 * slot for slot and dp for dp.
 *
 * What is ours is what goes in the slots and the pictures: the brand, its colour and its
 * artwork come from the rules XML (`PickupRule`) and the artwork packed into this apk, the code
 * and product from our own reading of the page. A brand with no picture draws the card without
 * one rather than not at all - the code is what the island is for.
 *
 * Two colours cannot come from the rules, because the official card puts its content on the
 * backdrop SystemUI draws (day or night), not on a background of its own: the rule under the
 * code, and the text under it. They are the ones 小爱's own card uses - `#1affffff` and its
 * neighbours - so [night] picks the pair, and the notification carries both cards.
 */
internal object PickupCard {

    private const val DEFAULT_CODE = "#3482FF"
    private const val DEFAULT_BUTTON = "#3482FF"

    /** The card's own text, per theme: what 小爱's card uses over its day and night backdrop. */
    private const val DAY_TEXT = 0xFF000000.toInt()
    private const val NIGHT_TEXT = 0xFFFFFFFF.toInt()
    private const val DAY_SUBTITLE = 0x99000000.toInt()
    private const val NIGHT_SUBTITLE = 0x99FFFFFF.toInt()
    private const val DAY_RULE = 0x1A000000
    private const val NIGHT_RULE = 0x1AFFFFFF

    /**
     * [alpha] is the picture's opacity, and it is a parameter because it is the only way this card
     * can be animated: a RemoteViews carries no animation of any kind, so a fade has to be posted
     * as a series of notifications that each draw the pictures a little more opaque (see
     * [PickupCodeIsland.fade]). 1f is the resting state.
     */
    fun build(
        c: Context,
        rule: PickupRule?,
        code: String,
        product: String?,
        store: String?,
        brand: String,
        tap: PendingIntent,
        alpha: Float = 1f,
        night: Boolean = false,
    ): RemoteViews {
        val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_pickup_card)

        // An Icon, not a bitmap and not a resource id: a Bitmap is frozen pixels - one frame of a
        // 53-frame animation - while an Icon is handed to the system to resolve, so what the
        // ImageView ends up holding is the animated drawable and it plays.
        val file = PickupArt.model(rule, brand, product)
        val model = file?.let { PickupArt.drawable(c, it) } ?: 0
        // The whole chain in one line: which picture the rules asked for, whether this build can
        // reach it by name, and what the card was told to draw. Three different failures look the
        // same on a lock screen.
        Xp.log("MCPickupCard: brand=$brand rule=${rule?.brandName ?: "-"} model=$file" +
            " id=$model code=$code product=${product ?: "-"} store=${store ?: "-"} night=$night")
        if (model != 0) {
            views.setImageViewIcon(R.id.mc_pickup_icon,
                Icon.createWithResource(BuildConfig.APPLICATION_ID, model))
        } else {
            views.setViewVisibility(R.id.mc_pickup_icon, View.INVISIBLE)
        }
        // The brand's logo is the thing that fades in, so it is what this fades.
        val step = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        if (step < 255) views.setInt(R.id.mc_pickup_icon, "setImageAlpha", step)

        views.setTextViewText(R.id.mc_pickup_title, code)
        // The official card sets its code at 28sp whatever it says; a code long enough to be cut
        // off by the one line it is given steps down instead of trailing off.
        val size = if (code.any { it.code > 127 } || code.length > 6) 24f else 28f
        views.setTextViewTextSize(R.id.mc_pickup_title, TypedValue.COMPLEX_UNIT_SP, size)
        colour(rule?.pickupColor, DEFAULT_CODE)?.let {
            views.setTextColor(R.id.mc_pickup_title, it)
        }

        views.setTextViewText(R.id.mc_pickup_product, product?.ifEmpty { null } ?: brand)
        views.setTextColor(R.id.mc_pickup_product, if (night) NIGHT_TEXT else DAY_TEXT)
        views.setTextViewText(R.id.mc_pickup_store, store.orEmpty())
        views.setTextColor(R.id.mc_pickup_store, if (night) NIGHT_SUBTITLE else DAY_SUBTITLE)
        // The rule between them is a TextView with nothing in it, drawn by colour alone.
        views.setInt(R.id.mc_pickup_divider, "setBackgroundColor",
            if (night) NIGHT_RULE else DAY_RULE)

        // The pill: a rounded rectangle drawn here, because a RemoteViews cannot tint a shape
        // from resources but can be handed a bitmap, and the radius (56dp, as in the official
        // card) is well past half the height, so it comes out as the capsule it looks like.
        val button = colour(rule?.pickupButtonColor, DEFAULT_BUTTON)
        views.setImageViewBitmap(R.id.mc_pickup_action_bg, pill(c, button ?: 0xFF3482FF.toInt()))
        // What the pill says is ours, not the official card's 「确认取餐」: this button does not
        // confirm the pickup, it opens the order's own page.
        views.setTextViewText(R.id.mc_pickup_action, "查看订单")
        views.setTextColor(R.id.mc_pickup_action, 0xFFFFFFFF.toInt())
        views.setOnClickPendingIntent(R.id.mc_pickup_action_container, tap)
        views.setOnClickPendingIntent(R.id.mc_pickup_root, tap)
        return views
    }

    /** The card's button: a capsule of [fill], at the 56dp radius the official card is cut to. */
    private fun pill(c: Context, fill: Int): Bitmap {
        val density = c.resources.displayMetrics.density
        val width = (104 * density).toInt().coerceAtLeast(4)
        val height = (37 * density).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        Canvas(bitmap).drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(),
            56f * density, 56f * density, paint)
        return bitmap
    }

    /**
     * The brand's logo, for the slot the *system* draws the picture in: the island's own icon and
     * the lock screen's capsule both take it from `iconTextInfo.animIconInfo` and lay it out
     * themselves, with no card of ours involved.
     *
     * The logo rather than the card's drink, because of how that artwork is framed: the drink is a
     * 300x351 canvas whose cup - shadow included - ends 59px above the bottom edge (measured on
     * base_bg_tea_style_yellow.webp), so once it is scaled into that small square slot the cup
     * rides high and reads as a smudge. The logo is already a tight square and is legible at the
     * size the slot gives it, which is what the official card does there too.
     */
    fun logoIcon(rule: PickupRule?): Icon? =
        PickupArt.logo(rule)?.let { PickupArt.bitmap(it, 96) }?.let { Icon.createWithBitmap(it) }

    /**
     * The collapsed island: the brand's logo and the code, which is what the island is for.
     *
     * Its own layout and its own key (`miui.focus.rv.tiny`), because the small island is not the
     * big card shrunk - hiding the whole card there would leave the icon and no code.
     */
    fun tiny(c: Context, rule: PickupRule?, code: String, fallback: Bitmap, tap: PendingIntent): RemoteViews {
        val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.mc_pickup_tiny)
        val logo = PickupArt.logo(rule)?.let { PickupArt.bitmap(it, 72) }
        Xp.log("MCPickupTiny: code=$code logo=${PickupArt.logo(rule) ?: "-"}" +
            " drawn=${logo != null} fallback=${fallback != null}")
        views.setImageViewBitmap(R.id.mc_pickup_tiny_icon, logo ?: fallback)
        views.setTextViewText(R.id.mc_pickup_tiny_code, code)
        colour(rule?.pickupColor, DEFAULT_CODE)?.let {
            views.setTextColor(R.id.mc_pickup_tiny_code, it)
        }
        views.setOnClickPendingIntent(R.id.mc_pickup_tiny_icon, tap)
        views.setOnClickPendingIntent(R.id.mc_pickup_tiny_code, tap)
        return views
    }

    /** A colour out of the rules XML, or null when it is absent or unreadable. */
    private fun colour(value: String?, fallback: String): Int? = try {
        Color.parseColor(value?.takeIf { it.isNotEmpty() } ?: fallback)
    } catch (_: IllegalArgumentException) {
        try {
            Color.parseColor(fallback)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
