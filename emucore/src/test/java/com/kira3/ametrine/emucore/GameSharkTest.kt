package com.kira3.ametrine.emucore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSharkTest {
    @Test
    fun readsCodeLines() {
        val check = GameShark.check("8009c6e4 0fff\n  D0012345:1234 \n90012345 12345678")
        assertNull(check.badLine)
        assertEquals(listOf("8009C6E4 0FFF", "D0012345 1234", "90012345 12345678"), check.lines)
    }

    @Test
    fun skipsBlankLinesAndComments() {
        val check = GameShark.check("# infinite HP\n\n; note\n// more\n8009C6E4 0FFF")
        assertNull(check.badLine)
        assertEquals(listOf("8009C6E4 0FFF"), check.lines)
    }

    @Test
    fun pointsAtTheFirstBadLine() {
        val check = GameShark.check("8009C6E4 0FFF\npatch=1,EE,0012,word,1\n8009C6E4 0F")
        assertEquals(2, check.badLine)
        assertEquals(listOf("8009C6E4 0FFF"), check.lines)
    }
}
