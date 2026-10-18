package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioLevelMeterTest {

    /** 16-bit little-endian sine at [amplitude] (0..32767). */
    private fun tone(amplitude: Int, samples: Int = 1600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * sin(2 * PI * 440 * i / 16000.0)).roundToInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** Feeds [windows] windows of [chunk], one per 50 ms, and returns the last level. */
    private fun AudioLevelMeter.feed(chunk: ByteArray, windows: Int, startMs: Long = 0): Float {
        var last = 0f
        for (w in 0 until windows) last = offer(chunk, chunk.size, startMs + w * 50L)!!
        return last
    }

    @Test fun `decibels measures RMS and treats digital silence as very quiet`() {
        assertTrue(AudioLevelMeter.decibels(ByteArray(3200), 3200) < -100)
        // A full-scale sine has an RMS of 1/sqrt(2) of full scale: about -3 dBFS.
        assertEquals(-3.0, AudioLevelMeter.decibels(tone(32767), 3200), 0.1)
        assertTrue(AudioLevelMeter.decibels(tone(300), 3200) < AudioLevelMeter.decibels(tone(3000), 3200))
    }

    @Test fun `only the valid length is measured`() {
        val buf = tone(20000) + ByteArray(3200)
        assertEquals(AudioLevelMeter.decibels(tone(20000), 3200), AudioLevelMeter.decibels(buf, 3200), 0.0)
        assertTrue(AudioLevelMeter.decibels(buf, 1) < -100)
    }

    @Test fun `digital silence reads as zero`() {
        assertEquals(0f, AudioLevelMeter().feed(ByteArray(3200), 5))
    }

    @Test fun `steady room noise settles to zero so the waves lie flat`() {
        val meter = AudioLevelMeter()
        val noise = tone(460) // about -40 dBFS: a noisy room, louder than the starting estimate
        assertTrue("noise first shows a little", meter.offer(noise, noise.size, 0)!! > 0f)
        assertEquals(0f, meter.feed(noise, 80, startMs = 50))
    }

    @Test fun `speech over settled noise still uses most of the range`() {
        val meter = AudioLevelMeter()
        meter.feed(tone(460), 100)
        val speech = meter.offer(tone(3000), 3200, 100 * 50L)!!
        assertTrue("speech=$speech", speech > 0.5f)
        assertEquals(1f, meter.offer(tone(20000), 3200, 101 * 50L)!!)
    }

    @Test fun `a pause pulls the noise estimate back down after long speech`() {
        val meter = AudioLevelMeter()
        meter.feed(tone(3000), 40)
        val afterSpeech = meter.noiseDbForTest()
        meter.feed(tone(100), 6, startMs = 40 * 50L)
        assertTrue("speech must not pin the estimate up: ${meter.noiseDbForTest()} vs $afterSpeech", meter.noiseDbForTest() < -45)
    }

    @Test fun `emits at most once per window and reports the loudest chunk in it`() {
        val meter = AudioLevelMeter(intervalMs = 50)
        val first = meter.offer(tone(500), 3200, nowMs = 0)
        assertNotNull(first)
        assertTrue("first=$first", first!! in 0.1f..0.5f)
        assertNull(meter.offer(tone(20000), 3200, nowMs = 20))
        assertNull(meter.offer(tone(500), 3200, nowMs = 40))
        assertEquals(1f, meter.offer(tone(500), 3200, nowMs = 50)!!)
    }
}
