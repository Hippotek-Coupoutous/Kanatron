package com.kanatron.app.tuner

import com.kanatron.app.core.TunerPitchReading
import com.kanatron.app.core.WavFormat
import com.kanatron.app.core.pcm16At
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

private fun reading(note: String, cents: Double = 0.0, targetHz: Double = 110.0) =
    TunerPitchReading(note, targetHz * 2.0.pow(cents / 1200.0), cents, targetHz)

private fun ReadingFilter.feed(vararg readings: TunerPitchReading?, startMs: Long = 0L): List<TunerAudioState> =
    readings.mapIndexed { index, it -> next(it, startMs + index * 23L) }

class TunerReadingsTest {

    @Test
    fun aNoteShowsOnlyOnceThreeFramesAgree() {
        val shown = ReadingFilter().feed(reading("A2"), reading("A2"), reading("A2"))
        assertNull(shown[0].reading)
        assertNull(shown[1].reading)
        assertEquals("A2", shown[2].reading?.note)
    }

    @Test
    fun aTwoFrameOctaveJumpNeverReachesTheScreen() {
        val a2 = reading("A2")
        val a3 = reading("A3", targetHz = 220.0)
        val shown = ReadingFilter().feed(a2, a2, a2, a3, a3, a2, a2)
        assertEquals(listOf("A2", "A2", "A2", "A2", "A2"), shown.drop(2).map { it.reading?.note })
    }

    @Test
    fun theShownPitchIsTheMedianOfTheLastFiveFrames() {
        val shown = ReadingFilter().feed(*listOf(40.0, 40.0, 1.0, 2.0, 3.0, 2.0, 1.0).map { reading("A2", it) }.toTypedArray())
        // The two 40-cent frames lead the median until two later frames push them out of the five.
        assertEquals(listOf(40.0, 40.0, 3.0, 2.0, 2.0), shown.drop(2).map { it.reading!!.cents })
    }

    @Test
    fun inTuneNeedsFiveSteadyFramesAndLetsGoOnlyPastFiveCents() {
        val filter = ReadingFilter()
        val settling = filter.feed(*Array(7) { reading("A2", 1.0) })
        // The first in-tune frame is the third, when the note confirms; the fifth in a row turns it on.
        assertEquals(listOf(false, false, false, false, false, false, true), settling.map { it.inTune })
        val gap = filter.feed(*Array(3) { reading("A2", 4.5) }, startMs = 200)
        assertTrue("4.5 cents is inside the hysteresis gap", gap.last().inTune)
        val sharp = filter.feed(*Array(3) { reading("A2", 6.0) }, startMs = 300)
        assertEquals("lets go once the median passes 5 cents", listOf(true, true, false), sharp.map { it.inTune })
    }

    @Test
    fun holdKeepsTheLastNoteFor1500MillisecondsAfterItFades() {
        val filter = ReadingFilter()
        assertNull("nothing to hold yet", filter.next(null, 0).reading)
        val note = filter.feed(reading("A2"), reading("A2"), reading("A2"), startMs = 1_000).last().reading
        assertTrue(filter.next(null, 2_000).holding)
        assertEquals(note, filter.next(null, 2_546).reading)
        assertNull(filter.next(null, 2_547).reading)
        assertNull("a note after the hold confirms afresh", filter.next(reading("A2"), 2_570).reading)
    }

    @Test
    fun theAnalyzerUpdatesEveryHopAndStaysDeafWhileItsBeepRings() {
        val rate = 48_000
        val analyzer = TunerAnalyzer(rate)
        val tone = FloatArray(4096 + 15 * 1024) { (0.5 * kotlin.math.sin(2 * Math.PI * 440.0 * it / rate)).toFloat() }
        val shown = tone.asList().mapNotNull { analyzer.add(it, 440.0) }
        assertEquals("one state per 1024-sample hop", 16, shown.size)
        assertEquals(listOf(null, null, "A4"), shown.take(3).map { it.reading?.note })
        assertEquals(6, shown.indexOfFirst { it.inTune })
        // Deaf for 150 ms of beep plus one window: 7200 + 4096 samples, 11 hops of the same state.
        assertTrue(shown.subList(6, 16).all { it === shown[6] })
    }

    @Test
    fun aRecordedLowETuned8CentsFlatReadsE2AndNeverClaimsInTune() {
        val shown = analyze(recording("Low E"))
        assertEquals(setOf("E2"), shown.mapNotNull { it.reading?.note }.toSet())
        assertTrue(shown.none { it.inTune })
        assertEquals(-8.0, shown.last { it.reading != null }.reading!!.cents, 2.0)
    }

    @Test
    fun aRecordedInTuneAPluckReadsA2AndBeepsOnce() {
        val shown = analyze(recording("A Pluck"))
        assertEquals(setOf("A2"), shown.mapNotNull { it.reading?.note }.toSet())
        assertEquals(1, beeps(shown))
    }

    @Test
    fun theBeepHeardByTheMicrophoneLeavesTheReadingAlone() {
        val pluck = recording("A Pluck")
        val clean = analyze(pluck)
        val firstInTune = clean.indexOfFirst { it.inTune }
        assertTrue("the pluck reaches in tune", firstInTune >= 0)
        val beepAt = (firstInTune + 1) * 1024 + 3072
        // Full-scale beep in the mic: 2.5x the string's level, a worst case for a phone's speaker beside its mic.
        val beep = inTuneBeep(clean[firstInTune].reading!!.targetHz, 44_100)
        val heard = pluck.copyOf().also { pcm -> beep.forEachIndexed { i, s -> pcm[beepAt + i] += s / 32768f } }
        val withBeep = analyze(heard)
        assertEquals(clean.map { it.reading?.note }, withBeep.map { it.reading?.note })
        assertEquals("no flicker, so no second beep", clean.map { it.inTune }, withBeep.map { it.inTune })
    }

    private fun beeps(states: List<TunerAudioState>) = states.zipWithNext().count { (a, b) -> !a.inTune && b.inTune }

    private fun analyze(pcm: FloatArray): List<TunerAudioState> {
        val analyzer = TunerAnalyzer(44_100)
        return pcm.asList().mapNotNull { analyzer.add(it, 440.0) }
    }

    /** Local acoustic guitar takes, converted to 44.1 kHz mono WAV; gitignored, so these tests skip elsewhere. */
    private fun recording(name: String): FloatArray {
        val file = File("../test-audio-samples/$name.wav")
        assumeTrue("$file is a local, gitignored recording", file.exists())
        val bytes = file.readBytes()
        val format = WavFormat.read(bytes.inputStream())
        assertTrue(format.sampleRate == 44_100 && format.channels == 1)
        val start = format.dataOffset.toInt()
        return FloatArray(format.frameCount.toInt()) { pcm16At(bytes, start + it * 2) / 32768f }
    }
}
