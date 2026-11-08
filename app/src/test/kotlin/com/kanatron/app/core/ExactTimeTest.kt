package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactTimeTest {

    @Test
    fun parseExactTimeReadsSecondsMinutesAndHours() {
        assertEquals(0L, millis("0"))
        assertEquals(12_000L, millis("12"))
        assertEquals(1_500L, millis("1.5"))
        assertEquals(1_500L, millis("0:01.5"))
        // 1 minute + 30 seconds.
        assertEquals(90_000L, millis("1:30"))
        assertEquals(60_000L, millis("60"))
        // 2 hours.
        assertEquals(7_200_000L, millis("2:00:00"))
        // 1×3 600 000 + 2×60 000 + 3.250×1000.
        assertEquals(3_723_250L, millis("01:02:03.250"))

        val minute = parseExactTimeMillis("1:00")!!
        val padded = parseExactTimeMillis("00:01:00.000")!!
        assertEquals(60_000L, minute.longValueExact())
        assertEquals(60_000L, padded.longValueExact())
        // Same instant, different BigDecimal scale.
        assertEquals(0, minute.compareTo(padded))

        listOf(
            "",
            "-1",
            "1e3",
            "1 :30",
            "1:60",
            "0:60",
            "1:60:00",
            "1:2:60",
            "1:2:3:4",
            "1:2:",
            "1::2",
            // Hours × 3 600 000 overflows Long.
            "99999999999999:00:00"
        ).forEach { text -> assertNull(text, parseExactTimeMillis(text)) }
    }

    private fun millis(text: String): Long = parseExactTimeMillis(text)!!.longValueExact()
}
