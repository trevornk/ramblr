package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmNormalizerTest {
    private fun near(expected: Int, actual: Int, tol: Int) = assertTrue("expected ~$expected got $actual", abs(expected - actual) <= tol)

    private fun run(inRate: Int, channels: Int, float: Boolean, frames: Int, gen: (Int, Int) -> Double): List<Short> {
        val out = ArrayList<Short>()
        val n = PcmNormalizer(inRate, channels, float) { s, c -> for (i in 0 until c) out += s[i] }
        val bytesPer = if (float) 4 else 2
        val buf = ByteBuffer.allocate(frames * channels * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) {
            val v = gen(f, c)
            if (float) buf.putFloat(v.toFloat()) else buf.putShort((v * 32767).toInt().toShort())
        }
        buf.flip()
        // feed in uneven slices on frame boundaries to exercise state carry-over
        val frameBytes = channels * bytesPer
        var left = buf.remaining()
        var pos = 0
        val step = frameBytes * 333
        while (left > 0) {
            val take = minOf(step, left)
            val slice = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN); slice.position(pos); slice.limit(pos + take)
            n.feed(slice, take)
            pos += take; left -= take
        }
        n.finish()
        return out
    }

    @Test fun `16k mono passes through unchanged`() {
        val out = run(16000, 1, false, 1000) { f, _ -> if (f % 2 == 0) 0.5 else -0.5 }
        assertEquals(1000, out.size)
        near(16383, out[0].toInt(), 2)
    }

    @Test fun `48k to 16k yields one third the samples`() {
        val out = run(48000, 1, false, 48000) { _, _ -> 0.25 }
        near(16000, out.size, 1)
        near(8192, out[100].toInt(), 4)
    }

    @Test fun `44_1k to 16k yields the right count`() {
        val out = run(44100, 1, false, 44100) { _, _ -> 0.1 }
        near(16000, out.size, 1)
    }

    @Test fun `stereo is averaged to mono`() {
        val out = run(16000, 2, false, 500) { _, c -> if (c == 0) 0.8 else -0.2 }
        assertEquals(500, out.size)
        near((0.3 * 32768).toInt(), out[10].toInt(), 40)
    }

    @Test fun `float pcm is converted and clipped`() {
        val out = run(16000, 1, true, 100) { _, _ -> 2.0 }
        assertEquals(Short.MAX_VALUE, out[5])
    }

    @Test fun `a downsampled tone keeps its amplitude and frequency`() {
        // 440 Hz at 48k -> 16k: zero crossings per second should stay ~880.
        val out = run(48000, 1, false, 48000) { f, _ -> 0.5 * sin(2 * PI * 440 * f / 48000.0) }
        var crossings = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0) != (out[i] < 0)) crossings++
        near(880, crossings, 6)
        assertTrue(out.maxOf { abs(it.toInt()) } > 14000)
    }

    @Test fun `shortsToBytes is little endian`() {
        val b = PcmNormalizer.shortsToBytes(shortArrayOf(0x0102, -2), 2)
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), b)
    }
}
