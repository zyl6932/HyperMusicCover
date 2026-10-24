package com.os4.musiccover.lyrics;
import org.junit.Test;
import static org.junit.Assert.*;
public class LyricEntryMotionTest {
 @Test public void opacityDropDoesNotReverseArrival() {
  LyricEntryMotion m=new LyricEntryMotion();
  m.step(true,true,0.016f,true);
  for(int i=0;i<100;i++) m.step(true,false,0.016f,false);
  assertEquals(1f,m.progress(),0f);
  assertEquals(1f,LyricEntryMotion.opacity(1f,0f,true),0f);
 }
 @Test public void reversesImmediatelyAndWaitsForFirstLayout() {
  LyricEntryMotion m=new LyricEntryMotion();
  m.step(true,false,1f,false); assertEquals(0f,m.progress(),0f);
  m.step(true,true,0.016f,false); float p=m.progress();
  m.step(false,true,0.016f,false); assertTrue(m.progress()<p);
  p=m.progress(); m.step(true,false,0.016f,false); assertTrue(m.progress()>p);
  for(int i=0;i<200;i++) m.step(false,false,0.016f,false);
  assertEquals(0f,m.progress(),0f); assertFalse(m.moving());
 }
 @Test public void fixedBandSurvivesClockMotionAndRapidReversal() {
  LyricBandGeometry.FixedBand b=new LyricBandGeometry.FixedBand(); Object style=new Object();
  assertFalse(b.update(1,1080,2400,style,false,Float.NaN,Float.NaN));
  assertTrue(b.update(1,1080,2400,style,false,400,1600));
  b.update(1,1080,2400,style,true,200,1800); assertEquals(400,b.top(),0);
  b.update(2,1080,2400,style,true,250,1800); assertEquals(400,b.top(),0);
  b.update(3,1080,2400,style,false,300,1700); assertEquals(300,b.top(),0);
  b.update(3,2400,1080,style,true,100,800); assertEquals(100,b.top(),0);
 }
}
