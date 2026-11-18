package com.os4.musiccover

import com.os4.musiccover.app.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OS4 switch as it travels through a backup file.
 *
 * A file written before the switch existed has no key, and those are exactly the installs being
 * upgraded - they get the new interface, like a fresh install. A file written after it has to
 * bring the choice back, or importing a backup would quietly flip the user's interface.
 */
class AppSettingsJsonTest {

    @Test
    fun absentKeyMeansOs4() {
        assertTrue(AppSettings.fromJson("{}").useOs4Ui)
    }

    @Test
    fun os3ChoiceSurvivesExportAndImport() {
        val exported = AppSettings(useOs4Ui = false).toJson()
        assertFalse(AppSettings.fromJson(exported).useOs4Ui)
    }

    @Test
    fun os4ChoiceIsWrittenExplicitly() {
        val exported = AppSettings(useOs4Ui = true).toJson()
        assertTrue(exported.contains("\"useOs4Ui\": true"))
    }
}
