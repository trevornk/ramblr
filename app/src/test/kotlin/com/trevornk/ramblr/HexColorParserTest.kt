package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexColorParserTest {

    @Test
    fun `parses with leading hash and forces opaque alpha`() {
        val result = HexColorParser.parse("#FF00FF")
        assertEquals((0xFFFF00FF).toInt(), result)
    }

    @Test
    fun `parses without leading hash`() {
        val result = HexColorParser.parse("00FF00")
        assertEquals((0xFF00FF00).toInt(), result)
    }

    @Test
    fun `is case insensitive`() {
        assertEquals(HexColorParser.parse("aabbcc"), HexColorParser.parse("AABBCC"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val result = HexColorParser.parse("  #123456  ")
        assertEquals((0xFF123456).toInt(), result)
    }

    @Test
    fun `rejects wrong length`() {
        assertNull(HexColorParser.parse("#FFF"))
        assertNull(HexColorParser.parse("#FFFFFFFF"))
        assertNull(HexColorParser.parse(""))
    }

    @Test
    fun `rejects non-hex characters`() {
        assertNull(HexColorParser.parse("#GGGGGG"))
        assertNull(HexColorParser.parse("zzzzzz"))
    }

    @Test
    fun `black and white round trip correctly`() {
        assertEquals((0xFF000000).toInt(), HexColorParser.parse("000000"))
        assertEquals((0xFFFFFFFF).toInt(), HexColorParser.parse("FFFFFF"))
    }
}
