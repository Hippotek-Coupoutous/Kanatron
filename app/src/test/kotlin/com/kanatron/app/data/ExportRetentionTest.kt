package com.kanatron.app.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private var clock = 1_700_000_000_000L

    @Test
    fun retentionKeepsRecentExportsAndReclaimsOnlyAfterTheSevenDayGrace() {
        val root = temp.newFolder("root")
        val exports = File(root, "exports").apply { mkdirs() }
        val recent = File(exports, "recent.wav").apply { writeBytes(ByteArray(4)) }
        val boundary = File(exports, "boundary.wav").apply { writeBytes(ByteArray(4)) }
        val old = File(exports, "old.wav").apply { writeBytes(ByteArray(4)) }
        assertTrue(recent.setLastModified(clock - 6 * day))
        // canRetire uses >=, so a file exactly 7 days old is reclaimed.
        assertTrue(boundary.setLastModified(clock - 7 * day))
        assertTrue(old.setLastModified(clock - 8 * day))
        val retention = ExportRetention(root, { clock }, maxBytes = 1_000)

        retention.cleanup()
        assertTrue(recent.isFile)
        assertFalse(boundary.exists())
        assertFalse(old.exists())

        val recent2 = retention.write("proj-1", "Recent2.wav", 800) {
            it.writeBytes(ByteArray(800))
            it.setLastModified(clock)
        }
        retention.cleanup()
        assertTrue(recent2.isFile)
        assertEquals(800L, recent2.length())

        assertThrows(IOException::class.java) {
            retention.write("proj-1", "New.wav", 400) { it.writeBytes(ByteArray(400)) }
        }
        assertFalse(File(exports, "New.wav").exists())
        assertEquals(800L, recent2.length())
        assertTrue(recent.isFile)

        clock += 8 * day
        val fresh = retention.write("proj-1", "New.wav", 400) {
            it.writeBytes(ByteArray(400))
            it.setLastModified(clock)
        }
        assertTrue(fresh.isFile)
        assertEquals(400L, fresh.length())
        // Recent2 was stamped at the pre-shift clock, so it is now 8 days old.
        assertFalse(recent2.exists())
        // recent.wav was already 6 days old when stamped, so it is now 14 days past its stamp.
        assertFalse(recent.exists())
    }

    @Test
    fun neverOverwritesAnExportAndCapsLongNamesAt240Characters() {
        val root = temp.newFolder("root")
        val exports = File(root, "exports").apply { mkdirs() }
        val now = 1_700_000_000_000L
        val retention = ExportRetention(root, { now })

        val first = retention.write("proj-1", "Song.wav", 4) {
            it.writeBytes(byteArrayOf(1, 1, 1, 1))
            it.setLastModified(now)
        }
        val second = retention.write("proj-1", "Song.wav", 4) {
            it.writeBytes(byteArrayOf(2, 2, 2, 2))
            it.setLastModified(now)
        }
        assertEquals(first.parentFile.canonicalFile, second.parentFile.canonicalFile)
        assertNotEquals(first.name, second.name)
        assertTrue(first.name.length <= 240)
        assertTrue(second.name.length <= 240)
        assertTrue(first.name.endsWith(".wav"))
        assertTrue(second.name.endsWith(".wav"))
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), first.readBytes())
        assertArrayEquals(byteArrayOf(2, 2, 2, 2), second.readBytes())

        val longName = "b".repeat(240 - ".wav".length) + ".wav"
        assertEquals(240, longName.length)
        val longFirst = retention.write("proj-1", longName, 4) {
            it.writeBytes(byteArrayOf(3, 3, 3, 3))
            it.setLastModified(now)
        }
        val longSecond = retention.write("proj-1", longName, 4) {
            it.writeBytes(byteArrayOf(4, 4, 4, 4))
            it.setLastModified(now)
        }
        assertEquals(longName, longFirst.name)
        assertNotEquals(longFirst.name, longSecond.name)
        // " (2)" is 4 characters, so the stem must be cut to 240 - 3 - 1 - 4 = 232 to stay at the 240 cap.
        assertEquals(240, longSecond.name.length)
        assertTrue(longSecond.name.endsWith(".wav"))
        assertArrayEquals(byteArrayOf(3, 3, 3, 3), longFirst.readBytes())
    }
}
