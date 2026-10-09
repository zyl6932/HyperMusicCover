package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerPresentationPolicyTest {
    @Test fun dismissedMediaCannotBlockAWaitingNotificationExchange() {
        for (native in listOf(false, true)) for (cover in listOf(false, true)) {
            assertFalse(MiniPlayerPresentationPolicy.mediaExpanded(false, true, false, native, cover))
        }
        assertTrue(MiniPlayerPresentationPolicy.mediaExpanded(true, true, false, true, false))
        assertTrue(MiniPlayerPresentationPolicy.mediaExpanded(true, true, false, false, true))
        assertFalse(MiniPlayerPresentationPolicy.mediaExpanded(true, true, true, true, true))
    }

    private fun presentation(
        nativeRequested: Boolean = false,
        keyguardOwned: Boolean = true,
        sceneVisible: Boolean = true,
        enabled: Boolean = true,
        sessionUsable: Boolean = true,
        nativeSceneOverride: Boolean = false,
        transitionActive: Boolean = false,
        controlCenterOpen: Boolean = false,
        miniShowsMusic: Boolean = true,
    ) = MiniPlayerPresentationPolicy.evaluate(
        MiniPlayerPresentationInput(
            enabled,
            sessionUsable,
            nativeRequested,
            keyguardOwned,
            sceneVisible,
            nativeSceneOverride,
            transitionActive,
            controlCenterOpen,
            miniShowsMusic,
        ),
    )

    @Test fun dynamicNativeChoiceSurvivesUpdatesWithinTheSameSession() {
        val selection = MiniPlayerSessionSelection()
        val session = Any()
        selection.observe(session)
        selection.requestNative(session)

        // A track update observes the same MediaSession token and must not reset the choice.
        selection.observe(session)

        assertTrue(selection.nativeRequestedFor(session))
        assertEquals(MiniPlayerPresentation(false, false),
            presentation(nativeRequested = selection.nativeRequestedFor(session)))
    }

    @Test fun dynamicChoiceResetsForANewOrDestroyedSession() {
        val selection = MiniPlayerSessionSelection()
        val first = Any()
        val second = Any()
        selection.requestNative(first)

        selection.observe(second)
        assertFalse(selection.nativeRequestedFor(second))

        selection.requestNative(second)
        selection.end(second)
        assertFalse(selection.nativeRequestedFor(second))
    }

    @Test fun temporarySceneOcclusionNeverHidesBothPlayers() {
        val aodOrControlCenter = presentation(sceneVisible = false)

        assertFalse(aodOrControlCenter.showMini)
        assertFalse(aodOrControlCenter.suppressNative)
        assertEquals(MiniPlayerPresentation(true, true), presentation(sceneVisible = true))
    }

    @Test fun translucentControlCenterKeepsMiniSelectionAndNativeSuppression() {
        assertEquals(MiniPlayerPresentation(true, true), presentation(
            sceneVisible = false, controlCenterOpen = true))
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            sceneVisible = false, controlCenterOpen = true, nativeRequested = true))
    }

    @Test fun nativeChoiceDisplaysOnlyTheVendorCard() {
        assertEquals(MiniPlayerPresentation(false, false), presentation(nativeRequested = true))
    }

    @Test fun coverAndLyricsTemporarilyGiveTheNativeCardOwnership() {
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            sceneVisible = false,
            nativeSceneOverride = true,
        ))
    }

    @Test fun manuallyOpenedCoverHidesMusicEvenIfTheLockscreenStillReportsTheRowVisible() {
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            sceneVisible = true, nativeSceneOverride = true))
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            sceneVisible = false, controlCenterOpen = true, nativeSceneOverride = true))
    }

    @Test fun swipeThenArtworkTapShowsOnlyTheExpandedPlayerAfterTheMorphSettles() {
        val selection = MiniPlayerSessionSelection()
        val session = Any()
        selection.requestNative(session)
        assertEquals(MiniPlayerPresentation(true, false), presentation(
            nativeRequested = true, nativeSceneOverride = true, transitionActive = true))
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            nativeRequested = selection.nativeRequestedFor(session), nativeSceneOverride = true))
        // A refresh during row rearrangement may temporarily mask the native choice.
        assertEquals(MiniPlayerPresentation(false, false), presentation(
            nativeRequested = false, nativeSceneOverride = true))
    }

    @Test fun notificationIslandsRemainVisibleBesideTheCoverPlayer() {
        assertEquals(MiniPlayerPresentation(true, false), presentation(
            sceneVisible = true, nativeSceneOverride = true, miniShowsMusic = false))
        assertEquals(MiniPlayerPresentation(true, true), presentation(
            sceneVisible = true, miniShowsMusic = false))
    }

    @Test fun transitionKeepsBothShellsAvailableWithoutHardSuppression() {
        assertEquals(MiniPlayerPresentation(true, false), presentation(
            sceneVisible = false,
            nativeSceneOverride = true,
            transitionActive = true,
        ))
    }

    @Test fun leavingTheKeyguardRestoresNativeWithoutChangingTheChoice() {
        assertEquals(MiniPlayerPresentation(false, false),
            presentation(keyguardOwned = false, sceneVisible = false))
    }

    @Test fun disabledOrEndedSessionRestoresNative() {
        assertEquals(MiniPlayerPresentation(false, false), presentation(enabled = false))
        assertEquals(MiniPlayerPresentation(false, false), presentation(sessionUsable = false))
    }

    @Test fun visibilityAndAlphaAreRestoredForEveryHeaderGeneration() {
        class Header(var visibility: Int, var alpha: Float)
        val first = Header(0, 0.8f)
        val second = Header(0, 0.6f)
        val registry = WeakViewOverrideRegistry<Header>()

        fun suppress(header: Header) = registry.suppress(
            header, header.visibility, header.alpha,
        ) { state ->
            header.visibility = state.visibility
            header.alpha = state.alpha
        }
        suppress(first)
        suppress(second)
        assertEquals(4, first.visibility)
        assertEquals(0f, first.alpha, 0f)
        assertEquals(4, second.visibility)

        registry.restore { header, state ->
            header.visibility = state.visibility
            header.alpha = state.alpha
        }
        assertEquals(0, first.visibility)
        assertEquals(0.8f, first.alpha, 0f)
        assertEquals(0, second.visibility)
        assertEquals(0.6f, second.alpha, 0f)
    }
}
