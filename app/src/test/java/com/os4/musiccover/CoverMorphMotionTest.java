package com.os4.musiccover;

import com.os4.musiccover.cover.CoverMorphMotion;
import com.os4.musiccover.card.CardSpring;
import org.junit.Test;

import static org.junit.Assert.*;

public class CoverMorphMotionTest {
    @Test public void curvedPathKeepsBothEndpointsAndReversesOnItself() {
        CoverMorphMotion.Box thumb = new CoverMorphMotion.Box(70f, 1460f, 158f, 158f);
        CoverMorphMotion.Box cover = new CoverMorphMotion.Box(58f, 485f, 826f, 826f);
        CoverMorphMotion.Box start = CoverMorphMotion.frame(thumb, cover, 0f, 3f);
        CoverMorphMotion.Box end = CoverMorphMotion.frame(thumb, cover, 1f, 3f);
        CoverMorphMotion.Box middle = CoverMorphMotion.frame(thumb, cover, 0.5f, 3f);
        assertEquals(thumb.x, start.x, 0.001f);
        assertEquals(thumb.y, start.y, 0.001f);
        assertEquals(cover.x, end.x, 0.001f);
        assertEquals(cover.y, end.y, 0.001f);
        assertTrue(middle.cx() > (thumb.cx() + cover.cx()) * 0.5f);
        assertEquals(middle.cx(), CoverMorphMotion.frame(thumb, cover, 0.5f, 3f).cx(), 0f);
    }

    @Test public void springCanReverseMidFlightAndLandAtThumbnail() {
        CoverMorphMotion motion = new CoverMorphMotion();
        motion.aim(true);
        for (int i = 0; i < 12; i++) motion.step(1f / 120f, 0.38f);
        assertTrue(motion.value > 0f && motion.value < 1f);
        float atReverse = motion.value;
        motion.aim(false);
        motion.step(1f / 120f, 0.38f);
        assertTrue(Math.abs(motion.value - atReverse) < 0.1f);
        for (int i = 0; i < 240; i++) motion.step(1f / 120f, 0.38f);
        assertTrue(motion.atRest());
        assertEquals(0f, motion.value, 0f);
    }

    @Test public void responseIsIndependentOfRefreshRate() {
        CoverMorphMotion sixty = new CoverMorphMotion(), oneTwenty = new CoverMorphMotion();
        sixty.aim(true);
        oneTwenty.aim(true);
        for (int i = 0; i < 24; i++) sixty.step(1f / 60f, 0.38f);
        for (int i = 0; i < 48; i++) oneTwenty.step(1f / 120f, 0.38f);
        assertEquals(sixty.value, oneTwenty.value, 0.0001f);
        assertEquals(sixty.velocity, oneTwenty.velocity, 0.0001f);
    }

    @Test public void pausedCardEndpointMatchesTheDrawnSquareInBothDirections() {
        CoverMorphMotion.Box thumb = new CoverMorphMotion.Box(70f, 1460f, 150f, 150f);
        CoverMorphMotion.Box paused = CoverMorphMotion.cardSquare(58f, 485f, 800f,
                CardSpring.PAUSED);
        assertEquals(98f, paused.x, 0.001f);
        assertEquals(525f, paused.y, 0.001f);
        assertEquals(720f, paused.w, 0.001f);
        assertEquals(paused.w, CoverMorphMotion.frame(thumb, paused, 1f, 3f).w, 0.001f);
        assertEquals(thumb.w, CoverMorphMotion.frame(thumb, paused, 0f, 3f).w, 0.001f);
        CoverMorphMotion.Box playing = CoverMorphMotion.cardSquare(58f, 485f, 800f,
                CardSpring.PLAYING);
        assertEquals(800f, playing.w, 0.001f);
        assertEquals(paused.cx(), playing.cx(), 0.001f);
        assertEquals(paused.cy(), playing.cy(), 0.001f);
    }

    @Test public void shadowAndOutlineReachFullStrengthWithTheArtwork() {
        assertEquals(0f, CoverMorphMotion.cardDecoration(0f), 0f);
        assertEquals(0.5f, CoverMorphMotion.cardDecoration(0.5f), 0.0001f);
        assertEquals(1f, CoverMorphMotion.cardDecoration(1f), 0f);
        assertTrue(CoverMorphMotion.cardDecoration(0.7f)
                > CoverMorphMotion.cardDecoration(0.3f));
        assertEquals(1f, CoverMorphMotion.cardDecoration(1.02f), 0f);
    }
}
