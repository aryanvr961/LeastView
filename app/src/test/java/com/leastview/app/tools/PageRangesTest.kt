package com.leastview.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PageRangesTest {
    private fun invalid(input: String, pages: Int = 10): String {
        try {
            PageRanges.parse(input, pages)
        } catch (e: PageRanges.Invalid) {
            return e.message.orEmpty()
        }
        fail("\"$input\" should have been rejected")
        return ""
    }

    @Test fun parsesSinglePagesRangesAndOpenEnds() {
        assertEquals(listOf(0..2, 4..4, 7..9), PageRanges.parse("1-3, 5, 8-", 10))
        assertEquals(listOf(0..0), PageRanges.parse(" 1 ", 3))
        assertEquals(listOf(2..2, 0..0), PageRanges.parse("3;1", 3)) // typed order is kept, ';' works like ','
        assertEquals(listOf(0..4), PageRanges.parse("1-5,", 5))     // trailing comma is harmless
    }

    @Test fun rejectsRangesOutsideTheDocument() {
        assertTrue(invalid("11").contains("outside"))
        assertTrue(invalid("5-12").contains("outside"))
        assertTrue(invalid("0").contains("valid"))
    }

    @Test fun rejectsOverlapsAndBackwardsRanges() {
        assertTrue(invalid("1-5, 4-6").contains("overlaps"))
        assertTrue(invalid("3, 3").contains("overlaps"))
        assertTrue(invalid("5-2").contains("starts after"))
    }

    @Test fun rejectsGarbageAndEmptyInput() {
        assertTrue(invalid("").isNotEmpty())
        assertTrue(invalid("  , ").isNotEmpty())
        assertTrue(invalid("a-b").contains("valid"))
        assertTrue(invalid("1-2-3").contains("valid"))
        assertTrue(invalid("-3").contains("valid"))
    }

    @Test fun labelsReadNaturally() {
        assertEquals("5", PageRanges.label(4..4))
        assertEquals("2-4", PageRanges.label(1..3))
    }
}
