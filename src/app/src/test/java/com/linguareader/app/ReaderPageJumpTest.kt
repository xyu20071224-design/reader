package com.linguareader.app

import com.linguareader.app.ui.reader.parsePageInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderPageJumpTest {
    @Test
    fun validPageParsesToZeroBasedIndex() {
        assertEquals(0, parsePageInput("1", 5))
        assertEquals(4, parsePageInput("5", 5))
        assertEquals(2, parsePageInput("3", 3))
    }

    @Test
    fun outOfRangeOrNonNumericInputIsRejected() {
        assertNull(parsePageInput("0", 5))
        assertNull(parsePageInput("6", 5))
        assertNull(parsePageInput("abc", 5))
        assertNull(parsePageInput("", 5))
        assertNull(parsePageInput("2", 0))
    }
}