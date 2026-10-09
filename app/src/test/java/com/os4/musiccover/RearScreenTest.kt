package com.os4.musiccover

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The back screen's own record of what it has on it, and the one entry in it that is ours.
 *
 * `widget_with_our_card.json` is the real file, read off the phone on 2026-10-09: one entry, for a
 * card that had been cancelled fifty minutes earlier (`|mc-59954685|`), which the app's restore was
 * therefore bringing back with no RemoteViews in it - the black box. What has to come out of it is
 * that one entry and only that one: the other cards on that screen - 小爱's own pickup card, which
 * carries the same `business` as ours - belong to the app as much as this file does.
 */
class RearScreenTest {

    private fun file(name: String): JSONArray =
        JSONArray(javaClass.classLoader!!.getResource("pickup/$name.json")!!.readText())

    private fun entry(pkg: String, key: String): String = JSONObject()
        .put("id", 1)
        .put("type", 3)
        .put("changed", true)
        .put("extra", JSONObject().put("package_name", pkg).put("notification_key", key))
        .put("nfc", false)
        .toString()

    @Test
    fun ourCardComesOut() {
        val widgets = file("widget_with_our_card")
        assertEquals(1, RearScreen.drop(widgets))
        assertEquals(0, widgets.length())
    }

    @Test
    fun aFileWithNothingOfOursIsLeftAlone() {
        val widgets = file("widget_with_our_card")
        RearScreen.drop(widgets)
        // The next start of the app reads the same clean file: nothing to do, and nothing written.
        assertEquals(0, RearScreen.drop(widgets))
        assertEquals(0, widgets.length())
    }

    @Test
    fun anotherAppsCardAndOurOtherNotificationsStay() {
        val widgets = JSONArray(
            listOf(
                // 小爱's pickup card: the same business ours carries, a different package.
                entry("com.miui.voiceassist", "0|com.miui.voiceassist|279017488|null|10201"),
                // SystemUI's own notifications are ours to leave alone: no tag of this module's.
                entry("com.android.systemui", "0|com.android.systemui|2012875145|UNIMPORTANT|10224"),
                // And a card of ours, which is the only thing here that goes.
                entry("com.android.systemui", "0|com.android.systemui|1242|mc-60058897|10224"),
            ).joinToString(",", prefix = "[", postfix = "]"))
        assertEquals(1, RearScreen.drop(widgets))
        assertEquals(2, widgets.length())
    }

    @Test
    fun aTagThatOnlyLooksLikeOursIsNotTouched() {
        // `mc-` is this module's tag, but the entry has to be SystemUI's as well: the package is
        // what the widget is keyed on, and another app's file entry is not ours to remove.
        val widgets = JSONArray(
            "[" + entry("com.example.other", "0|com.example.other|7|mc-1234|10001") + "]")
        assertEquals(0, RearScreen.drop(widgets))
        assertEquals(1, widgets.length())
    }

    @Test
    fun anEntryWeCannotReadIsLeftAlone() {
        val widgets = JSONArray(
            """[{"id":7,"type":3,"changed":false},{"id":8,"type":3,"changed":false,"extra":null}]""")
        assertEquals(0, RearScreen.drop(widgets))
        assertEquals(2, widgets.length())
    }
}
