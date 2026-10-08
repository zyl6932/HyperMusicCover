package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

class MetroCommuteTest {

    private val now = at(10, 8, 40)

    /** Day [day] of October 2026 at [h]:[m], local time. */
    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, day, h, m)
    }.timeInMillis

    private fun trip(start: String, end: String, at: Long, vararg changes: String, city: String = "北京") =
        MetroCommute.Trip(city, start, end, changes.toList(), at)

    @Test
    fun threeTripsMakeACommute() {
        val c = MetroCommute(null)
        c.record(trip("西二旗", "国贸", at(6, 8, 10), "知春路"), now)
        c.record(trip("西二旗", "国贸", at(7, 8, 20), "知春路"), now)
        assertTrue(c.routes(now).isEmpty())
        c.record(trip("西二旗", "国贸", at(8, 8, 5), "知春路"), now)
        val r = c.routes(now).single()
        assertEquals(listOf("知春路"), r.transfers)
        assertEquals(3, r.trips)
        assertEquals(3, r.slices[MetroCommute.slice(at(1, 8, 0))])
    }

    @Test
    fun aChangeNeedsAFifthOfTheTrips() {
        val group = List(6) { trip("A", "D", at(it + 1, 8, 0), "B") } +
            trip("A", "D", at(8, 8, 0), "C") + // 1 of 7: under 20%
            trip("A", "D", at(9, 8, 0), "B")
        assertEquals(listOf("B"), MetroCommute.transfers(group))
        // Two kept; the most common sequence of them.
        val two = listOf(trip("A", "D", 1, "B", "C"), trip("A", "D", 2, "B", "C"), trip("A", "D", 3, "C"))
        assertEquals(listOf("B", "C"), MetroCommute.transfers(two))
    }

    @Test
    fun matchedByTheHalfHourThenByTheStartAlone() {
        val c = MetroCommute(null)
        repeat(3) { c.record(trip("西二旗", "国贸", at(it + 1, 8, 10), "知春路"), now) }
        repeat(3) { c.record(trip("西二旗", "回龙观", at(it + 4, 19, 10)), now) }
        assertEquals("国贸", c.match("北京", "西二旗", at(10, 8, 15), now)?.end)
        assertEquals("回龙观", c.match("北京", "西二旗", at(10, 19, 0), now)?.end)
        // Two commutes from here, neither ridden in this half hour: no guess.
        assertNull(c.match("北京", "西二旗", at(10, 13, 0), now))
        assertNull(c.match("上海", "西二旗", at(10, 8, 15), now))
    }

    @Test
    fun theStartAloneGivesTheEndButNoChanges() {
        val c = MetroCommute(null)
        repeat(3) { c.record(trip("西二旗", "国贸", at(it + 1, 8, 10), "知春路"), now) }
        val r = c.match("北京", "西二旗", at(10, 21, 0), now)!!
        assertEquals("国贸", r.end)
        assertTrue(r.transfers.isEmpty())
        assertEquals(setOf("国贸"), r.stops)
    }

    @Test
    fun aMonthOldTripIsDropped() {
        val c = MetroCommute(null)
        c.record(trip("A", "B", at(1, 8, 0) - 31L * 86_400_000L), now)
        assertEquals(0, c.count())
    }

    @Test
    fun changesAreWhereTheLineBeforeAndAfterShareNothing() {
        val stations = listOf("西二旗", "上地", "知春路", "大钟寺", "西直门")
        val lines = mapOf(
            "西二旗" to setOf("13号线", "昌平线"),
            "上地" to setOf("13号线"),
            "知春路" to setOf("13号线", "10号线"),
            "大钟寺" to setOf("13号线"),
            "西直门" to setOf("13号线", "2号线", "4号线"),
        )
        assertTrue(MetroCommute.changes(stations, lines).isEmpty())
        val via10 = listOf("上地", "知春路", "西土城")
        val l2 = lines + ("西土城" to setOf("10号线"))
        assertEquals(listOf("知春路"), MetroCommute.changes(via10, l2))
        // A station with no lines says nothing.
        assertTrue(MetroCommute.changes(listOf("上地", "知春路", "X"), lines).isEmpty())
    }

    @Test
    fun keptOnDiskAndForgotten() {
        val f = File.createTempFile("commute", ".json")
        f.delete()
        val c = MetroCommute(f)
        repeat(3) { c.record(trip("A", "B", System.currentTimeMillis() - it * 86_400_000L)) }
        assertEquals(3, MetroCommute(f).count())
        c.setLearning(false)
        c.record(trip("A", "C", System.currentTimeMillis()))
        val back = MetroCommute(f)
        assertFalse(back.learning)
        assertEquals(3, back.count())
        back.forget()
        assertEquals(0, MetroCommute(f).count())
        f.delete()
    }
}
