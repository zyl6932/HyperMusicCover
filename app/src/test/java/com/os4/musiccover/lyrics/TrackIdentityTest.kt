package com.os4.musiccover.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fields here are the ones the players published: #47 (QQ 音乐), #56 (汽水音乐), #127 (网易云
 * with 蓝牙设备歌词 on), Salt Player's from 2026-09-22 and QQ's off this phone 2026-10-06.
 */
class TrackIdentityTest {

    private val em = TrackIdentity.EM

    @Before
    fun forget() = TrackIdentity.reset()

    private fun id(
        pkg: String?, title: String, artist: String, album: String = "",
        dur: Long = 240_000L, mediaId: String = "",
    ): TrackIdentity = TrackIdentity.of(pkg, title, artist, album, dur, mediaId)

    // ------------------------------------------------------------------ #127

    private val jianxin = "剑心 (电视剧《古剑奇谭》片头曲) - 张杰"

    @Test
    fun neteaseSpellsTheSongBeforeASpacedHyphen() {
        val t = id(NETEASE, "孤剑指尖谈笑", jianxin, "拾")
        assertTrue(t.certain())
        assertEquals("剑心 (电视剧《古剑奇谭》片头曲)", t.song())
        assertEquals("张杰", t.singer())
        assertTrue(t.songIsRead())
    }

    @Test
    fun neteasePayloadIsTakenAndAStaleOneIsNot() {
        val t = id(NETEASE, "孤剑指尖谈笑", jianxin, "拾")
        // The payload's songName, 2026-11-01 log: the gate used to refuse it.
        assertTrue(t.accepts("剑心"))
        assertFalse(t.accepts("以父之名"))
    }

    @Test
    fun neteaseWithoutBluetoothLyricsIsLeftAsPublished() {
        val t = id(NETEASE, "剑心", "张杰", "拾")
        assertEquals("剑心", t.song())
        assertEquals("张杰", t.singer())
        assertFalse(t.songIsRead())
    }

    // ------------------------------------------------------------------ the scoring

    private fun candidate(title: String, artist: String) =
        LyricMatch.Candidate("1", title, artist, "拾", 240_000L, null)

    @Test
    fun eachReadingScoresAndOnlyTheRightOnePasses() {
        // A player not in the table, its TITLE moving with the singing: every reading tied at D.
        val pkg = "com.example.player"
        val first = id(pkg, "孤剑指尖谈笑", jianxin, "拾")
        TrackIdentity.noteTitle(pkg, "孤剑指尖谈笑", first.songKey())
        TrackIdentity.noteTitle(pkg, "握不住的执念", first.songKey())
        TrackIdentity.noteTitle(pkg, "孤剑指尖谈笑", first.songKey())
        val t = id(pkg, "孤剑指尖谈笑", jianxin, "拾")
        assertTrue(t.titleIsLine())
        assertFalse(t.certain())
        // Tied with TITLE a line: ARTIST is what is searched.
        assertNotEquals(TrackIdentity.AS_PUBLISHED, t.primary().shape)

        val right = candidate("剑心", "张杰")
        val scores = t.scored().associate { r ->
            r.shape to LyricMatch.score(right, LyricMatch.Wanted(listOf(r), "", "", "拾", 240_000L, null))
        }
        assertEquals(55, scores[TrackIdentity.AS_PUBLISHED])
        assertEquals(105, scores[TrackIdentity.SONG_FIRST])
        assertEquals(25, scores[TrackIdentity.SONG_LAST])

        val w = LyricMatch.Wanted(t.scored(), "", "", "拾", 240_000L, t)
        val p = LyricMatch.best(listOf(candidate("孤剑指尖谈笑", "群星"), right), w)
        assertTrue(p.passes())
        assertEquals("剑心", p.candidate.title)
        assertEquals(TrackIdentity.SONG_FIRST, p.reading.shape)

        // Confirmed: the song from now on, and this player's direction learned.
        val after = id(pkg, "握不住的执念", jianxin, "拾")
        assertTrue(after.certain())
        assertEquals("张杰", after.singer())
        val next = id(pkg, "第一句", "下一首 - 另一位", "别的专辑", 200_000L)
        assertEquals(listOf(TrackIdentity.SONG_FIRST),
            next.readings.filter { it.split() }.map { it.shape })
    }

    @Test
    fun aBareHyphenHalfScoresOnlyOnEquality() {
        val t = id("com.example.none", "Lingering", "A-Lin", "")
        val half = t.readings.first { it.exact && it.song == "Lin" }
        val w = LyricMatch.Wanted(listOf(half), "", "", "", 0L, null)
        // "Lin" is inside "Lingering" and "A" inside "Alina": containment would have given 80.
        assertEquals(0, LyricMatch.score(LyricMatch.Candidate("1", "Lingering", "Alina", "", 0L, null), w))
    }

    // ------------------------------------------------------------------ the other players

    @Test
    fun qishuiSpellsTheSongBeforeAnEmDash() {
        val artist = "霍希进行曲 (秋天的风她不曾见过桃花)${em}AKA时空恋人"
        val t = id(QISHUI, "作曲：AKA时空恋人", artist)
        assertTrue(t.certain())
        assertEquals("霍希进行曲 (秋天的风她不曾见过桃花)", t.song())
        assertEquals("AKA时空恋人", t.singer())
        assertTrue(t.accepts("霍希进行曲 (秋天的风她不曾见过桃花)", "AKA时空恋人"))
        // The previous song the bridge was still holding.
        assertFalse(t.accepts("我要的很简单，一个家有你在（明知故犯）", "耳机里的盲盒"))
    }

    @Test
    fun qishuiIsOneSongInBothItsShapes() {
        val sung = id(QISHUI, "作曲：AKA时空恋人", "霍希进行曲${em}AKA时空恋人", "霍希进行曲")
        val plain = id(QISHUI, "霍希进行曲", "AKA时空恋人", "霍希进行曲")
        assertEquals(sung.songKey(), plain.songKey())
        assertEquals(sung.song(), plain.song())
    }

    @Test
    fun saltSpellsTheSongAfterASpacedHyphen() {
        val artist = "G.E.M.邓紫棋 - 喜欢你"
        val off = id(SALT, "喜欢你", artist)
        assertTrue(off.certain())
        assertEquals("G.E.M.邓紫棋", off.singer())
        val on = id(SALT, "抹去雨水双眼无故地仰望", artist)
        assertTrue(on.accepts("喜欢你"))
        assertTrue(on.accepts("喜欢你", "G.E.M.邓紫棋"))
    }

    @Test
    fun qqSpellsTheSongBeforeABareHyphen() {
        val t = id(QQ, "line", "Miss Americana & The Heartbreak Prince-Taylor Swift")
        assertTrue(t.accepts("Miss Americana & The Heartbreak Prince", "Taylor Swift"))
    }

    @Test
    fun qqSingleIsReadByItsAlbum() {
        assertEquals("I'SLAND", id(QQ, "line", "I'sland-I'SLAND", "I'sland").singer())
        assertEquals("朱婧汐Akini Jing",
            id(QQ, "line", "不怕！bu pa！-朱婧汐Akini Jing", "不怕！bu pa！").singer())
    }

    @Test
    fun anUnknownPlayerIsTakenAsPublished() {
        val artist = "爱意侵占计划 (粤语版)-Simyee陈芯怡"
        // Off a playlist the album says nothing, TITLE is a line, and nothing has been seen.
        val t = id("com.example.none", "某一句歌词", artist, "最近访客")
        assertTrue(t.certain())
        assertEquals(artist, t.singer())
        assertEquals("A-Lin", id("com.example.none", "罪恶感", "A-Lin", "罪恶感").singer())
    }

    @Test
    fun aBareHyphenInANameAgreesWithNothingButItsHalves() {
        val t = id("com.example.none", "罪恶感", "A-Lin", "")
        assertFalse(t.accepts("给我一个理由忘记"))
        assertFalse(t.accepts("Lingering"))
    }

    // ------------------------------------------------------------------ the key

    @Test
    fun theKeyHoldsNeitherTitleNorSinger() {
        val a = id(QISHUI, "第一句", "歌${em}歌手甲", "专辑", 200_000L)
        val b = id(QISHUI, "第二句", "歌手甲", "专辑", 200_000L)
        assertEquals(a.songKey(), b.songKey())
        assertNotEquals(a.songKey(), id(QISHUI, "第一句", "歌${em}歌手甲", "专辑", 201_000L).songKey())
    }

    @Test
    fun aCatalogueIdKeysTheSong() {
        // QQ's placeholder duration: not believed, and the id takes its place.
        val a = id(QQ, "line one", "歌-歌手", "专辑", 60_000L, "102065756")
        val b = id(QQ, "歌", "歌手", "专辑", 60_000L, "102065756")
        assertEquals(0L, a.durationMs)
        assertEquals(a.songKey(), b.songKey())
        assertNotEquals(a.songKey(), id(QQ, "歌", "歌手", "专辑", 60_000L, "102065757").songKey())
        // Something that is not a catalogue's id is left out of a key that has a duration.
        assertEquals(id(OTHER, "t", "a", "al", 200_000L, "content://x/1").songKey(),
            id(OTHER, "t", "a", "al", 200_000L, "content://x/2").songKey())
    }

    @Test
    fun noMetadataIsNoKey() {
        assertEquals("", id(OTHER, "", "", "", 0L).songKey())
        assertEquals("", id(null, "t", "a", "al").songKey())
    }

    // ------------------------------------------------------------------ sticking

    @Test
    fun whatASongHasEarnedOutlastsTheNextLine() {
        val pkg = "com.example.player"
        val t = id(pkg, "第一句", "歌名 - 歌手", "专辑")
        // Not certain from the fields: a payload says which.
        assertTrue(t.accepts("歌名"))
        val later = id(pkg, "第二句", "歌名 - 歌手", "专辑")
        assertTrue(later.certain())
        assertEquals("歌名", later.song())
        assertEquals(TrackIdentity.A, later.best().level)
    }

    @Test
    fun twoTitlesOfOneSongAreOneTrack() {
        val t = id(QISHUI, "作曲：AKA时空恋人", "霍希进行曲${em}AKA时空恋人", "霍希进行曲")
        TrackIdentity.noteTitle(QISHUI, "霍希进行曲", t.songKey())
        TrackIdentity.noteTitle(QISHUI, "作曲：AKA时空恋人", t.songKey())
        assertTrue(TrackIdentity.sameSong(QISHUI, "霍希进行曲", "作曲：AKA时空恋人"))
        assertFalse(TrackIdentity.sameSong(QISHUI, "霍希进行曲", "别的歌"))
    }

    private companion object {
        const val NETEASE = "com.netease.cloudmusic"
        const val QISHUI = "com.luna.music"
        const val SALT = "com.salt.music"
        const val QQ = "com.tencent.qqmusic"
        const val OTHER = "com.example.other"
    }
}
