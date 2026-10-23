package com.os4.musiccover.lyrics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalLyricsPriorityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = javaClass.getResource("/lyrics/overlapping-backgrounds.ttml")!!.readText()

    private fun select(audio: String, formats: Array<String>): java.io.File? =
        LocalLyricFiles.first(audio, formats) { file ->
            file.takeIf { LyricParse.parse(it.readText()).isNotEmpty() }
        }

    @Test fun sidecarOrderFallsBackOnParseFailureAndNeverMatchesAnotherSong() {
        val song = temporary.newFile("track.flac")
        val lrc = temporary.newFile("track.lrc").apply { writeText("[00:01.00]Local line\n[00:02.00]Last") }
        val ttml = temporary.newFile("track.ttml").apply { writeText(fixture()) }
        temporary.newFile("other.ttml").writeText(fixture())
        assertEquals(ttml.path, select(song.path, LocalLyricFormat.formats("ttml"))!!.path)
        assertEquals(lrc.path, select(song.path, LocalLyricFormat.formats("lrc"))!!.path)
        ttml.writeText("<tt><body>broken")
        assertEquals(lrc.path, select(song.path, LocalLyricFormat.formats("ttml"))!!.path)
        lrc.delete()
        assertNull(select(song.path, LocalLyricFormat.formats("ttml")))
    }

    @Test fun preferredMissingOrEmptyFallsBackInBothDirections() {
        val song = temporary.newFile("track.flac")
        val ttml = temporary.newFile("track.ttml").apply { writeText(fixture()) }
        assertEquals(ttml, select(song.path, LocalLyricFormat.formats("lrc")))
        val lrc = temporary.newFile("track.lrc")
        assertEquals(ttml, select(song.path, LocalLyricFormat.formats("lrc")))
        lrc.writeText("[00:01.00]Valid")
        ttml.writeText("")
        assertEquals(lrc, select(song.path, LocalLyricFormat.formats("ttml")))
        ttml.delete()
        assertEquals(lrc, select(song.path, LocalLyricFormat.formats("ttml")))
        lrc.writeText("no timestamps")
        assertNull(select(song.path, LocalLyricFormat.formats("ttml")))
    }

}
