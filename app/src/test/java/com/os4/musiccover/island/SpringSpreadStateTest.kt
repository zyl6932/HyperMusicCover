package com.os4.musiccover.island

import org.junit.Assert.*
import org.junit.Test

class SpringSpreadStateTest {
    @Test fun uninterruptedMotionMatchesThePreviouslyTestedOpeningCurve() {
        val spring = SpreadSpring(0f, 0.4f, IslandSpringMotion.BOUNCE)
        spring.target = 1f
        repeat(24) { frame ->
            spring.step(1f / 120f)
            assertEquals(IslandSpringMotion.progress((frame + 1) / 120f, 0.4f), spring.value, 0.0001f)
        }
    }

    @Test fun reversingPreservesPositionAndVelocityThenSettlesAtTheNewEnd() {
        val pose = SpringSpreadState(0f, 0.35f, 0.45f)
        pose.aim(true)
        for (t in 0L..90L step 10) pose.advance(t)
        val position = pose.position.value
        val velocity = pose.position.velocity
        val blur = pose.blurPx
        pose.aim(false)
        assertEquals(position, pose.position.value, 0f)
        assertEquals(velocity, pose.position.velocity, 0f)
        assertEquals(blur, pose.blurPx, 0f)
        for (t in 100L..190L step 10) pose.advance(t)
        val closingPosition = pose.position.value
        val closingVelocity = pose.position.velocity
        assertTrue(closingVelocity < 0f)
        pose.aim(true)
        assertEquals(closingPosition, pose.position.value, 0f)
        assertEquals(closingVelocity, pose.position.velocity, 0f)
        for (t in 200L..2000L step 10) pose.advance(t)
        assertTrue(pose.atRest())
        assertEquals(1f, pose.position.value, 0f)
        assertEquals(0f, pose.blurPx, 0f)
    }

    @Test fun repeatedFastReversalsStayFiniteAndCloseWithoutResidualBlurOwnership() {
        val pose = SpringSpreadState(0f)
        pose.aim(true)
        var now = 0L
        repeat(20) { turn ->
            pose.aim(turn % 2 == 0)
            repeat(5) {
                pose.advance(now)
                now += 10
                assertTrue(pose.position.value.isFinite())
                assertTrue(pose.position.value in -1f..2f)
                assertTrue(pose.blurPx in 0f..70f)
            }
        }
        pose.aim(false)
        repeat(180) { now += 10; pose.advance(now) }
        assertTrue(pose.atRest())
        assertEquals(0f, pose.position.value, 0f)
        assertEquals(0f, pose.opacity.value, 0f)
        assertEquals(70f, pose.blurPx, 0f)
    }

    @Test fun blurAndOpacityUseSeparateOpeningAndClosingResponses() {
        val opening = SpringSpreadState(0f).also { it.aim(true) }
        val closing = SpringSpreadState(1f).also { it.aim(false) }
        for (t in 0L..300L step 10) { opening.advance(t); closing.advance(t) }
        assertTrue(opening.blurPx < 5f)
        assertTrue(closing.blurPx > 55f)
        assertTrue(closing.opacity.value > closing.sharpness.value)
        assertEquals(0.9f, closing.opacity.response, 0f)
        assertEquals(0.4f, closing.sharpness.response, 0f)
    }

    @Test fun aLateVisibleRowDoesNotSkipItsSmallBlurredStartingPose() {
        val pose = SpringSpreadState(0f).also { it.aim(true) }
        pose.advance(100L, ready = false)
        pose.advance(400L, ready = false)
        assertFalse(pose.started)
        pose.advance(500L, ready = true)
        assertEquals(0f, pose.position.value, 0f)
        assertEquals(70f, pose.blurPx, 0f)
        pose.advance(516L)
        assertTrue(pose.position.value > 0f && pose.position.value < 0.1f)
        for (t in 526L..2006L step 10) pose.advance(t)
        assertTrue(pose.atRest())
    }

    @Test fun closingUsesTheReverseLevelOrderFromTheDex() {
        assertEquals(0.45f, IslandSpringMotion.response(0, 7, opening = false), 0.0001f)
        assertEquals(0.35f, IslandSpringMotion.response(6, 7, opening = false), 0.0001f)
        assertEquals(0.4f, IslandSpringMotion.response(0, 2, opening = false), 0.0001f)
        assertEquals(0.35f, IslandSpringMotion.response(1, 2, opening = false), 0.0001f)
    }

    @Test fun aContinuousGestureCanReverseInBothDirectionsWithoutLifting() {
        val gesture = SpringSpreadSwipe(500f, open = true)
        assertNull(gesture.move(599f))
        assertEquals(false, gesture.move(600f))
        assertNull(gesture.move(550f))
        assertEquals(true, gesture.move(500f))
        assertNull(gesture.move(450f))
        assertNull(gesture.move(480f))
        assertNull(gesture.move(500f))
        assertEquals(false, gesture.move(550f))
    }

    @Test fun cancellationAndDirectionJitterDoNotFlipTheDestination() {
        val gesture = SpringSpreadSwipe(0f, open = true)
        repeat(10) { assertNull(gesture.move(40f)); assertNull(gesture.move(0f)) }
        assertNull(gesture.release(4000f, cancelled = true))
        assertTrue(gesture.targetOpen)
        assertEquals(false, gesture.release(2000f, cancelled = false))
        assertEquals(true, gesture.release(-2000f, cancelled = false))
    }

    @Test fun islandAndNotificationContentCrossBeforeTheGeometryHasSettled() {
        val pose = SpringSpreadState(0f).also { it.aim(true) }
        for (t in 0L..100L step 10) pose.advance(t)
        assertFalse(pose.position.atRest())
        assertTrue(pose.opacity.value > 0f)
        assertTrue(SpringMorphContent.islandAlpha(pose.sharpness.value) < 0.25f)
        assertTrue(SpringMorphContent.islandBlur(pose.sharpness.value) > 35f)
        assertTrue(SpringMorphContent.rowBlur(pose.sharpness.value, 1f) < 35f)
        val alpha = SpringMorphContent.islandAlpha(pose.sharpness.value)
        val blur = SpringMorphContent.rowBlur(pose.sharpness.value, 0.5f)
        pose.aim(false)
        assertEquals(alpha, SpringMorphContent.islandAlpha(pose.sharpness.value), 0f)
        assertEquals(blur, SpringMorphContent.rowBlur(pose.sharpness.value, 0.5f), 0f)
        for (t in 110L..1700L step 10) pose.advance(t)
        assertEquals(1f, SpringMorphContent.islandAlpha(pose.sharpness.value), 0f)
        assertEquals(0f, SpringMorphContent.islandBlur(pose.sharpness.value), 0f)
        assertEquals(70f, SpringMorphContent.rowBlur(pose.sharpness.value, 0.5f) * 0.5f, 0f)
    }
}
