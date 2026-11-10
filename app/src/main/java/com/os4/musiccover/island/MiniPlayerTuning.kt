package com.os4.musiccover.island

// The mini player's tuning: the springs, timings, sizes and names MiniPlayerController
// works with, moved out of the end of its file as they were (MiniPlayerController plan, M0).

/** The small island's nudge home: MiniPlayerView's OFFSET_RESPONSE, CoverMorphMotion's damping. */
internal const val SMALL_NUDGE_RESPONSE = 0.32f

internal const val SMALL_NUDGE_DAMPING = 0.8f

/** Between the row of islands and the fingerprint sensor it is lifted above (#66). */
internal const val FOD_MARGIN_DP = 12f

/** How long the pill waits, down, for a scene its morph has just landed into. */
internal const val SCENE_WAIT_MS = 1000L

/** After the doze, the buttons are the lock screen's again within this long whatever happens. */
internal const val HOLD_MAX_MS = 2000L

/** ...and once its own alpha has been back at full for this long. */
internal const val HOLD_SETTLE_MS = 300L

/** In a doze that does not hold the buttons, the row faded below this is gone (unheldInDoze). */
internal const val DOZE_GONE = 0.05f

/** A disc's frame against its diameter: room for the widest swelling and squeeze. */
internal const val DISC_FRAME = 1.6f

/** A flight's home: the small island's circle, or the pill. */
internal const val HOME_SMALL = 0

internal const val HOME_PILL = 1

/**
 * The row's layout spring: CHANGE_EASE's response with more give, so the pill overshoots its
 * new width and comes back - and squeezes with its speed, [ROW_SQUASH] of its height per
 * width-per-second, at most [ROW_SQUASH_MAX].
 */
internal const val ROW_DAMPING = 0.7f

internal const val ROW_SQUASH = 0.02f

internal const val ROW_SQUASH_MAX = 0.08f

/** What a switch does with the small island: see Swap.smallMode. */
internal const val SMALL_KEPT = 0

internal const val SMALL_FROM_PILL = 1

internal const val SMALL_POP = 2

internal const val SMALL_EMERGE = 3

internal const val SMALL_FROM_GHOST = 4

internal const val SMALL_STACK = 5

/** The small island's pulse (smallIslandScaleAnimation): 1.1, sinInOut 200ms each way after 100ms. */
internal const val PULSE_SCALE = 1.1f

internal const val PULSE_MS = 200f

internal const val PULSE_DELAY_MS = 100f

/** The fastest a switch cut short hands its speed on, in its progress a second. */
internal const val SWAP_CARRY_MAX = 8f

/** From here on, a stand-in shrinking into the small island hands over to it. */
internal const val GHOST_HANDOFF = 0.85f

/** What kind of switch: see Swap.kind. */
/** Where the card coming down in an expanded switch lands: the big place, the small one, hiding. */
internal const val LAND_PILL = 0

internal const val LAND_SMALL = 1

internal const val LAND_HIDDEN = 2

internal const val SWAP_PAIR = 0

internal const val SWAP_NEXT = 1

internal const val SWAP_PREV = 2

internal const val SWAP_STACK = 3

/**
 * The super island's other springs (FolmeEase.spring(damping, response)): APPEAR 0.7/0.5,
 * SHOW 0.95/0.35, HIDDEN 1.0/0.2 (0.99 here: the spring's closed form divides by
 * sqrt(1 - damping^2)), ALPHA 0.95/0.15.
 */
internal const val APPEAR_RESPONSE = 0.5f

internal const val APPEAR_DAMPING = 0.7f

/**
 * The row coming up out of nothing (applyAppear): how far below its place it starts, how small,
 * how blurred its content, and the content's two beats - the picture's starting scale and how
 * far left the text slides in from. The stretch is per unit of the spring's velocity: rising
 * (tall and narrow) and settling back off the overshoot (flat and wide), the latter stronger
 * since that velocity is the smaller one.
 */
internal const val APPEAR_RISE_DP = 40f

internal const val APPEAR_SCALE_FROM = 0.75f

/** The width starts nearer its own size than the height does, and takes this share of the overshoot. */
internal const val APPEAR_WIDTH_FROM = 0.9f

internal const val APPEAR_WIDTH_OVERSHOOT = 0.4f

/** How far the torch and camera give, per pixel the pill stands wider than its place. */
internal const val APPEAR_PUSH_GAIN = 1.6f

internal const val APPEAR_BLUR_DP = 8f

internal const val APPEAR_ICON_FROM = 0.6f

internal const val APPEAR_TEXT_SHIFT_DP = 8f

internal const val APPEAR_STRETCH = 0.012f

internal const val APPEAR_LAND = 0.1f

internal const val APPEAR_SQUASH_MAX = 0.1f

/**
 * Damping 0.5 overshoots ~16%: 40dp of rise carries ~6dp past, the height to ~1.04. 0.55/0.45
 * took 1.3s to settle (`op mini`: up done 1324ms), which read as slow.
 */
internal const val ROW_POP_RESPONSE = 0.4f

internal const val ROW_POP_DAMPING = 0.5f

internal const val SHOW_RESPONSE = 0.35f

internal const val SHOW_DAMPING = 0.95f

internal const val HIDDEN_RESPONSE = 0.2f

internal const val HIDDEN_DAMPING = 0.99f

internal const val ALPHA_RESPONSE = 0.15f

internal const val ALPHA_DAMPING = 0.95f

/** HiddenToBigIsland brings the content in on HIDDEN_EASE with this delay. */
internal const val CONTENT_IN_DELAY_MS = 100L

/** A hidden island's size against its own (createHiddenProperties: 20% in on each side). */
internal const val HIDDEN_SCALE = 0.6f

/** The cutout's stand-in, as a share of the row's height. */
internal const val CUTOUT_SHARE = 0.55f

/** How far an island's content goes out of focus leaving the big island's place. */
/** ColorOS's capsule content blur, 9dp (SystemUIPlugin u4.k / u4.b0: j6.j.a(ctx, 9f)). */
internal const val SWAP_BLUR_DP = 9f

/** Every notification but the focus ones, as one island (LockIslands.stackMembers). */
internal const val STACK_ISLAND = LockIslands.STACK_KEY

/** An island switch's spring: the super island's CHANGE_EASE. */
internal const val SWAP_RESPONSE = 0.4f

internal const val SWAP_DAMPING = 0.82f

/** The super island's swipe progress: the pull over half the width, times this. */
internal const val SWIPE_SHARE = 0.14f

/** The stack island's other rows come in over this part of their own way out from under it (pileStack). */
internal const val PILE_IN_FROM = 0f

internal const val PILE_IN_TO = 0.3f

/** The stack is asked for its list for this long at most, every step, till it is one (showStackAsList). */
internal const val LIST_ASK_MS = 900L

internal const val LIST_ASK_STEP_MS = 50L

/** An island turned round is told to the stack once it has kept its way this long (turnTo). */
internal const val TURN_SETTLE_MS = 200L

/** The stack island's rows are looked for again after this long at the most (pileStack). */
internal const val PILE_FIND_MS = 8L

/** Each row's spring: a little softer than the switch's, so the last ones settle with a give. */
internal const val PILE_RESPONSE = 0.32f

internal const val PILE_DAMPING = 0.74f

/** How much of the lead's way each row starts behind the one before it, and at most. */
internal const val PILE_STAGGER = 0.12f

internal const val PILE_STAGGER_MAX = 0.48f

/** Live pictures kept per id for the next place to show (liveArt): the pill, the small island, a flight. */
internal const val LIVE_SPARES = 3

/** How long a pill whose island went while it was not on screen waits to be seen going back. */
internal const val BACK_WAIT_MS = 2500L

/** The rows settle in well under a second; past this the stack is not drawing them (pileSettle). */
internal const val PILE_SETTLE_MAX_MS = 2000L

/** A pull under the threshold goes home in this long. */
internal const val SPRING_BACK_MS = 320L

/** How long a notification folded back in may take to come back from the stack. */
internal const val RETURN_WAIT_MS = 1500L

/** The island in the pill missing from the reads this long keeps the pill (高德 re-posts every second). */
internal const val PICK_GRACE_MS = 2000L

/** An island missing this long is new when it comes back, and may take the pill again. */
internal const val KNOWN_FORGET_MS = 3000L

/** A row hidden on its way out of the stack shows again if it is still there after this. */
internal const val ROW_GONE_MS = 1200L

/** How long the row's session may read as stopped between two tracks and still be the music. */
internal const val USABLE_GAP_MS = 1000L

/** A row given back early is drawn on for its morph at most this long, whatever happens to it. */
internal const val KEPT_MAX_MS = 3000L

/** A row let out again that the stack has not taken back as a row by now is let go. */
internal const val REEADD_MAX_MS = 1000L

/** How long a released notification's row may take to be laid out before the pill moves on. */
internal const val ROW_WAIT_MS = 800L

/** The whole row out as the stack's list (Spread): where it is, and each island's place. */
internal const val SPREAD_WAIT = 0

internal const val SPREAD_OPENING = 1

internal const val SPREAD_OPEN = 2

internal const val SPREAD_CLOSING = 3

internal const val SEAT_NONE = -1

internal const val SEAT_BIG = 0

internal const val SEAT_SMALL = 1

internal const val SEAT_HIDDEN = 2

/** The stack's scroll unmoved this many frames, nothing dragging it: it is at rest. */
internal const val SPREAD_STILL_FRAMES = 3

/** A spread still between its ends this long after it set out is sent to the nearer one for good. */
internal const val SPREAD_GIVE_UP_MS = 3000L

/** A finger pulling the open list this far toward the fold takes the cards home with it. */
internal const val SPREAD_PULL_DP = 8f

/** The spread's frames stopping this long means the screen was off. */
internal const val SPREAD_SLEEP_GAP_MS = 700L

/** Woken, the stack has this long to be the list again before a fold counts as one. */
internal const val SPREAD_WAKE_MS = 1500L

/** Locked again with the list kept, the row has this long to show the stack island to open. */
internal const val LIST_KEPT_WAIT_MS = 1500L

/** The open list's lead row is held whole at most this long for the stack's own fade to reach it. */
internal const val LEAD_LET_GO_MAX_MS = 600L

/** The spread moving less than this since the clock was last asked: not worth asking again. */
internal const val CLOCK_FOLLOW_STEP = 0.002f

/** A pull home let go hands its speed on at most this (progress a second), as a morph's does. */
internal const val COLLAPSE_RELEASE_SPEED = 5f

/** Pulled home past this (from 1, the list), the let-go folds the row home: see spreadFolding. */
internal const val SPREAD_FOLD_BELOW = 0.95f

/** A pulled-open spread let go this fast goes the way it was flung, whatever its progress. */
internal const val SPREAD_FLING_DP = 800f

/** A pulled-open spread let go slowly opens from this far out, and folds back short of it. */
internal const val SPREAD_OPEN_AT = 0.35f

/** A row's picture, title and text, by the ids the notification templates give them. */
// A focus notification's row is the plugin's template: its picture (a Lottie view or a still),
// its chronometer or title, and its content line.
internal val ICON_NAMES = setOf("right_icon", "icon", "app_icon", "notification_icon", "left_icon",
    "focus_animation", "focus_animation_static", "focus_icon")

internal val TITLE_NAMES = setOf("title", "notification_title", "chronometer", "focus_title")

/** A focus template's picture, which the island's picture lands on before any icon. */
internal val FOCUS_ART_NAMES = setOf("focus_animation", "focus_animation_static", "focus_icon",
    "focus_large_icon", "focus_small_icon")

internal val TEXT_NAMES = setOf("text", "big_text", "notification_text", "focus_content")

/** Below this progress, a flight coming home fades off the small island shown under it. */
internal const val FLIGHT_HANDOFF = 0.18f

/** The longest a landed card is held where the stack is settling it, and the least. */
internal const val PIN_MS = 1500L

internal const val PIN_SETTLE_MS = 150L

/** The held card's spring toward where it is to be; how long a target left stale is believed. */
internal const val PIN_RESPONSE = 0.3f

/** Frames the stack's target has to hold, nothing leaving, before a landed card follows it. */
internal const val PIN_STEADY_FRAMES = 4

/**
 * The stack's fold, for the rows with no island of their own: pulled, a row follows the finger
 * with friction and goes at most a third of this; let go, it springs back on the stack's
 * release spring (NotificationNumStateAreaAnimator.releaseEase, FolmeEase.spring(1.0, 0.38)).
 */
internal const val FOLD_PULL_RANGE_DP = 150f

internal const val FOLD_BACK_RESPONSE = 0.38f

/** A row that came after the media card's morph set out comes up to its progress over this long. */
internal const val GROUP_JOIN_MS = 160L

/** Views kept for the next switch (prepareSmallView pooled): one going up, one coming down. */
internal const val SPARE_VIEWS = 2

internal const val STACK_SPARE_VIEWS = 4

/** The shortest a notification pull can be, island to row, in dp. */
internal const val NOTE_SPAN_MIN_DP = 160f
