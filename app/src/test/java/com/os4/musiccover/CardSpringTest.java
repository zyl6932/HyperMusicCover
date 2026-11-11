package com.os4.musiccover;

import com.os4.musiccover.card.CardSpring;
import org.junit.Test;

import static org.junit.Assert.*;

public class CardSpringTest {
    @Test public void playbackHasSmallReboundAndSettles() {
        CardSpring spring = new CardSpring();
        float smallest = spring.value;
        for (int i = 0; i < 120; i++) {
            spring.step(CardSpring.PAUSED, 1f / 120f);
            smallest = Math.min(smallest, spring.value);
        }
        assertTrue(smallest < CardSpring.PAUSED);
        assertEquals(CardSpring.PAUSED, spring.value, 0.001f);

        float largest = spring.value;
        for (int i = 0; i < 120; i++) {
            spring.step(CardSpring.PLAYING, 1f / 120f);
            largest = Math.max(largest, spring.value);
        }
        assertTrue(largest > CardSpring.PLAYING);
        assertEquals(CardSpring.PLAYING, spring.value, 0.001f);
    }

    @Test public void movementIsIndependentOfDisplayRefreshRate() {
        CardSpring sixty = new CardSpring(), oneTwenty = new CardSpring();
        for (int i = 0; i < 24; i++) sixty.step(CardSpring.PAUSED, 1f / 60f);
        for (int i = 0; i < 48; i++) oneTwenty.step(CardSpring.PAUSED, 1f / 120f);
        assertEquals(sixty.value, oneTwenty.value, 0.0001f);
        assertEquals(sixty.velocity, oneTwenty.velocity, 0.0001f);
    }
}
