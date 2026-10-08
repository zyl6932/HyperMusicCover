package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pages are real ones read off a phone on 2026-10-08 (shops, addresses and order
 * numbers replaced), one line a text node: the WebView it is in, a tab, the text.
 */
class PickupParseTest {

    private fun page(name: String): List<PickupParse.Node> =
        javaClass.classLoader!!.getResource("pickup/$name.txt")!!.readText().lines()
            .filter { it.isNotEmpty() }
            .map { val tab = it.indexOf('\t'); PickupParse.Node(it.substring(tab + 1), it.substring(0, tab).toInt()) }

    private fun n(vararg texts: String) = texts.map { PickupParse.Node(it, 0) }

    @Test
    fun mixueCodeIsUnderItsLabel() {
        val r = PickupParse.parse(page("mixue"))!!
        assertEquals("6706", r.code)
        assertEquals("取餐码", r.label)
        assertEquals("已完成", r.status)
        assertEquals("示例南门店", r.store)
    }

    @Test
    fun chageeCodeIsAboveItsLabel() {
        val r = PickupParse.parse(page("chagee"))!!
        assertEquals("T0295", r.code)
        assertEquals("取单号", r.label)
        assertEquals("已完成", r.status)
        assertEquals("示例市示例区时光里店", r.store)
    }

    @Test
    fun mcdCallsItsCodeTheOrderNumber() {
        // 订单号 / 35516, with the real order number under 订单编码 further down.
        val r = PickupParse.parse(page("mcd"))!!
        assertEquals("35516", r.code)
        assertEquals("订单号", r.label)
        assertEquals("已准备完毕", r.status)
        assertEquals("麦当劳示例广场餐厅", r.store)
    }

    @Test
    fun aStrongLabelBeatsAnEarlierWeakOne() {
        val r = PickupParse.parse(n("订单号", "123456", "取餐号", "A12"))!!
        assertEquals("A12", r.code)
    }

    @Test
    fun aWeakLabelWithALongNumberIsNoCode() {
        assertNull(PickupParse.parse(n("订单号", "2091126703119405058")))
    }

    @Test
    fun theHashIsNotPartOfTheCode() {
        assertEquals("123", PickupParse.parse(n("取餐码", "#123"))!!.code)
    }

    @Test
    fun aPageBehindIsNotRead() {
        // The order list (page 2) lies under the order (page 3); without page 3 there is no code.
        val listOnly = page("mixue").filter { it.page < 3 }
        assertNull(PickupParse.parse(listOnly))
    }

    @Test
    fun theShopsOwnNumberIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号码", "门店编码", "123456")))
    }

    @Test
    fun aPriceIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号", "¥", "18")))
    }

    @Test
    fun anOrderNumberIsNotTheCode() {
        assertNull(PickupParse.parse(n("取餐号", "2091126703119405058")))
    }

    @Test
    fun codeInTheLabelsOwnNode() {
        val r = PickupParse.parse(n("制作中", "取餐号：A123"))!!
        assertEquals("A123", r.code)
        assertEquals("取餐号", r.label)
        assertEquals("制作中", r.status)
    }

    @Test
    fun nothingWithoutALabel() {
        assertNull(PickupParse.parse(n("订单详情", "6706", "已完成")))
    }
}
