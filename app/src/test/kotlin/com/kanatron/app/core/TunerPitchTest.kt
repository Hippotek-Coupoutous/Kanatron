package com.kanatron.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private const val RATE = 48_000

private fun tone(frequencyHz: Double, rate: Int = RATE, sampleCount: Int = 4096, amplitude: Float = 0.5f) =
    FloatArray(sampleCount) { i -> (amplitude * sin(2.0 * PI * frequencyHz * i / rate)).toFloat() }

class TunerPitchTest {

    @Test
    fun detectsConcertAAtEveryNegotiatedSampleRate() {
        for (rate in intArrayOf(44_100, 48_000, 32_000, 22_050, 16_000)) {
            val reading = TunerPitch.detect(tone(440.0, rate), rate)
            assertNotNull("440 Hz at $rate Hz", reading)
            val detected = reading!!
            assertEquals("A4 at $rate Hz", "A4", detected.note)
            // 0.44 Hz is 0.1% of 440 Hz, under two cents; the detector's own error is 0.005 Hz.
            assertTrue("440 Hz at $rate Hz, got ${detected.frequencyHz}", abs(detected.frequencyHz - 440.0) <= 0.44)
            assertTrue("440 Hz at $rate Hz, got ${detected.cents} cents", abs(detected.cents) <= 2.0)
        }
    }

    @Test
    fun namesEveryEqualTemperedNoteOnTheChromaticScale() {
        val expected = listOf(
            60 to "C4", 61 to "C♯4", 62 to "D4", 63 to "D♯4", 64 to "E4", 65 to "F4",
            66 to "F♯4", 67 to "G4", 68 to "G♯4", 69 to "A4", 70 to "A♯4", 71 to "B4",
            72 to "C5", 76 to "E5"
        )
        for ((midi, note) in expected) {
            // Equal temperament around A4 = MIDI 69, with MIDI 60 numbered as C4.
            val inputHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val reading = TunerPitch.detect(tone(inputHz, sampleCount = 2048), RATE)
            assertNotNull("MIDI $midi at $inputHz Hz", reading)
            val detected = reading!!
            assertEquals("MIDI $midi at $inputHz Hz", note, detected.note)
            assertTrue("MIDI $midi at $inputHz Hz, got ${detected.cents} cents", abs(detected.cents) <= 1.0)
        }
    }

    @Test
    fun detectsTheAdvertisedFrequencyRangeEndpoints() {
        for (endpointHz in doubleArrayOf(TunerPitch.MIN_FREQUENCY_HZ, TunerPitch.MAX_FREQUENCY_HZ)) {
            val reading = TunerPitch.detect(tone(endpointHz), RATE)
            assertNotNull("$endpointHz Hz endpoint", reading)
            val detected = reading!!
            // 0.1% is 1.7 cents at 440 Hz; the observed error at both edges is under 0.008%.
            assertTrue(
                "$endpointHz Hz endpoint, got ${detected.frequencyHz}",
                abs(detected.frequencyHz - endpointHz) / endpointHz <= 0.001
            )
        }
    }

    @Test
    fun reportsSignedCentsAgainstTheNearestNoteAndSelectedA4() {
        data class Case(val inputHz: Double, val a4Hz: Double, val note: String, val cents: Double)
        val cases = listOf(
            Case(440.0 * 2.0.pow(25.0 / 1200.0), 440.0, "A4", 25.0),
            Case(440.0 * 2.0.pow(-25.0 / 1200.0), 440.0, "A4", -25.0),
            Case(440.0 * 2.0.pow(45.0 / 1200.0), 440.0, "A4", 45.0),
            // 55 cents above A4 is 45 cents below A♯4, so the label flips and the sign goes negative.
            Case(440.0 * 2.0.pow(55.0 / 1200.0), 440.0, "A♯4", -45.0),
            Case(415.0, 415.0, "A4", 0.0),
            // A♯4 is 415 * 2^(1/12) = 439.677 Hz, so 440 Hz reads +1.271 cents above it.
            Case(440.0, 415.0, "A♯4", 1.271)
        )
        for (case in cases) {
            val label = "${case.inputHz} Hz with A4 = ${case.a4Hz}"
            val reading = TunerPitch.detect(tone(case.inputHz), RATE, case.a4Hz)
            assertNotNull(label, reading)
            val detected = reading!!
            assertEquals(label, case.note, detected.note)
            assertTrue("$label, got ${detected.cents} cents", abs(detected.cents - case.cents) <= 1.0)
            assertTrue("$label, got ${detected.frequencyHz} Hz", abs(detected.frequencyHz - case.inputHz) / case.inputHz <= 0.001)
        }
    }

    @Test
    fun reportsTheFundamentalOfAHarmonicRichNote() {
        // 220, 330 and 440 Hz are harmonics of 110 Hz, so the waveform repeats every 1/110 s.
        val samples = FloatArray(4096) { i ->
            val second = 0.5 * sin(2.0 * PI * 220.0 * i / RATE)
            val third = 0.3 * sin(2.0 * PI * 330.0 * i / RATE)
            val fourth = 0.2 * sin(2.0 * PI * 440.0 * i / RATE)
            (second + third + fourth).toFloat()
        }
        val reading = TunerPitch.detect(samples, RATE)
        assertNotNull(reading)
        val detected = reading!!
        assertEquals("A2", detected.note)
        assertTrue("got ${detected.frequencyHz} Hz", abs(detected.frequencyHz - 110.0) <= 0.11)
        assertTrue("got ${detected.cents} cents", abs(detected.cents) <= 1.0)
    }

    @Test
    fun rejectsSilenceAndOneLsbButAcceptsAQuietNote() {
        assertNull("all zeros", TunerPitch.detect(FloatArray(4096), RATE))

        // One 16-bit PCM LSB has RMS 1 / (32768 * sqrt(2)) = 2.2e-5, below any real signal.
        assertNull("one LSB", TunerPitch.detect(tone(440.0, amplitude = 1f / 32768f), RATE))

        // 0.02 amplitude is 0.0141 RMS, about -34 dBFS peak: quiet but a real note.
        val reading = TunerPitch.detect(tone(440.0, amplitude = 0.02f), RATE)
        assertNotNull("quiet note", reading)
        val detected = reading!!
        assertEquals("A4", detected.note)
        assertTrue("got ${detected.cents} cents", abs(detected.cents) <= 2.0)
    }
}
