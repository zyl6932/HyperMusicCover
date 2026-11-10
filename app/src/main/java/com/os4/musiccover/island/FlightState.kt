package com.os4.musiccover.island

import com.os4.musiccover.CoverMorphMotion

/**
 * The state of a notification's morph between the row and its place in the stack, and of the
 * flight that carries it: what NoteMorphMotion starts and lands, and CardExchange, NoteDragMotion
 * and the rest read and hand over. Moved here as it was from NoteMorphMotion and the controller
 * (MiniPlayerController plan, M2), so the parts that write it are handed this and not each other.
 * Fields only: what they mean is NoteMorphMotion's.
 */
internal class FlightState {

    /**
     * The notification island on its way out into its row in the stack, or back in from it. The
     * pill keeps showing it until the morph lands, whatever the row's islands have become.
     */
    var noteMorphKey: String? = null

    /** A small island tapped: it takes the pill first, and opens once it has. */
    var pendingExpand: String? = null

    var rowWaitKey: String? = null
    var rowWaitSince = 0L
    var appListAsked = false

    /** The flight: a pill of its own, bound to [key]'s notification, laid out at the pill's size. */
    var flight: MiniPlayerView? = null
    var flightFromSmall = false

    /** Where the flight's island lives when it is in the row: the pill, or the small island. */
    var flightHome = HOME_SMALL

    /** Notifications let out of the pill itself: pulled down, their row goes back there. */
    val releasedFromPill = HashSet<String>()

    /** ...and islands let out of the small place, for the music, which otherwise goes to the pill. */
    val releasedFromSmall = HashSet<String>()

    /** The pill under a flight landing on it, taking the flight's shape; null otherwise. */
    var pillLandingBox: CoverMorphMotion.Box? = null

    /**
     * The flight has left the small island's place for good - a tap, or a pull let go past
     * the threshold - and the place is the row's again. Until then it is the flight's to come
     * back to.
     */
    var flightOut = false

    /**
     * The flight is on its last stretch home: the real small island is already in its place
     * under it and the flight fades off it. Swapped only once it had landed, the glass's
     * refraction and the picture both changed on that frame (filmed 2026-09-25).
     */
    var flightLanding = false

    /**
     * Landing, the small island is the flight's shape frame by frame - through the spring's
     * overshoot and back - and not its own rest circle: the flight fades off an island moving
     * exactly as it does. Put at rest under the flight, the small island stood still while the
     * flight went through the overshoot invisible, and the landing had no give at all, where
     * the pill's has (2026-09-25). On screen; null when not landing.
     */
    var landingBox: CoverMorphMotion.Box? = null
}
