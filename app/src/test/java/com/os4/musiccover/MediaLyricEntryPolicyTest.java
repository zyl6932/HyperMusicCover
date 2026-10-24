package com.os4.musiccover;
import org.junit.Test;
import static org.junit.Assert.*;
public class MediaLyricEntryPolicyTest {
 @Test public void capsulePageResumesFromArtworkEvenWithMissingCover() {
  for(boolean cover:new boolean[]{false,true}) assertEquals(MediaLyricEntryPolicy.Artwork.RESUME_LYRICS,MediaLyricEntryPolicy.artwork(true,cover));
  assertEquals(MediaLyricEntryPolicy.Artwork.EXIT_COVER,MediaLyricEntryPolicy.artwork(false,true));
  assertEquals(MediaLyricEntryPolicy.Artwork.ENTER_COVER,MediaLyricEntryPolicy.artwork(false,false));
 }
 @Test public void restoreRespectsNavigationAndOriginalCoverSetting() {
  assertTrue(MediaLyricEntryPolicy.restoreMusic(true,false));
  assertFalse(MediaLyricEntryPolicy.restoreMusic(false,false));
  assertFalse(MediaLyricEntryPolicy.restoreMusic(true,true));
 }
}
