package com.linguareader.shared.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL
import java.net.URLConnection

class ReleaseContentLengthTest {
    private fun value(header: String?, fallback: Int = -1): Long = ReleaseContentLength.read(object : URLConnection(URL("https://fixture.invalid")) {
        override fun connect() {}
        override fun getHeaderField(name: String?) = if (name.equals("Content-Length", true)) header else null
        override fun getContentLength() = fallback
        override fun getContentLengthLong(): Long = error("API24 method must not be used")
    })

    @Test fun `64 bit header wins over int fallback without overflow`() {
        assertEquals(4294967296L, value("4294967296", 12))
        assertEquals(Long.MAX_VALUE, value(Long.MAX_VALUE.toString(), 12))
        assertEquals(42L, value(" 42 ", 12))
        assertEquals(0L, value("0", 12))
    }

    @Test fun `missing malformed negative or overflowing header uses API1 fallback`() {
        for (header in listOf(null, "", "bad", "-1", "-99", "9223372036854775808")) {
            assertEquals(17L, value(header, 17))
            assertEquals(-1L, value(header))
        }
        assertEquals(-1L, value(null, -8))
    }
}
