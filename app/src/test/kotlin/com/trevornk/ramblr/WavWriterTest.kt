package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test

class WavWriterTest {

    @Test fun `empty pcm produces 44-byte header`() {
        val wav = WavWriter.encode(ByteArray(0))
        assertEquals(44, wav.size)
    }

    @Test fun `starts with RIFF`() {
        val wav = WavWriter.encode(ByteArray(0))
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
    }

    @Test fun `contains WAVE format`() {
        val wav = WavWriter.encode(ByteArray(0))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
    }

    @Test fun `file size field correct`() {
        val wav = WavWriter.encode(ByteArray(100))
        assertEquals(36 + 100, readInt(wav, 4))
    }

    @Test fun `data size field correct`() {
        val wav = WavWriter.encode(ByteArray(100))
        assertEquals(100, readInt(wav, 40))
    }

    @Test fun `sample rate 16kHz`() {
        val wav = WavWriter.encode(ByteArray(0), sampleRate = 16000)
        assertEquals(16000, readInt(wav, 24))
    }

    @Test fun `byte rate correct for 16kHz mono 16-bit`() {
        val wav = WavWriter.encode(ByteArray(0), sampleRate = 16000)
        assertEquals(32000, readInt(wav, 28)) // 16000 * 1 * 2
    }

    @Test fun `pcm data appended after header`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = WavWriter.encode(pcm)
        assertEquals(48, wav.size)
        assertArrayEquals(pcm, wav.copyOfRange(44, 48))
    }

    // -- header (used directly by the streaming upload path, see PcmWavRequestBodyTest) --

    @Test fun `header is 44 bytes regardless of pcm size`() {
        assertEquals(44, WavWriter.header(0L).size)
        assertEquals(44, WavWriter.header(123_456_789L).size)
    }

    @Test fun `header matches encode's header for the same pcm size`() {
        val pcm = ByteArray(500)
        val encoded = WavWriter.encode(pcm)
        val header = WavWriter.header(pcm.size.toLong())
        assertArrayEquals(encoded.copyOfRange(0, 44), header)
    }

    @Test fun `header data size field reflects a pcm size larger than any single write`() {
        val header = WavWriter.header(5_000_000L)
        assertEquals(5_000_000, readInt(header, 40))
        assertEquals(36 + 5_000_000, readInt(header, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `header rejects a pcm size that would overflow the 32-bit size field (L13)`() {
        WavWriter.header(Int.MAX_VALUE.toLong() + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `header rejects a negative pcm size (L13)`() {
        WavWriter.header(-1L)
    }

    private fun readInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        ((buf[off + 2].toInt() and 0xFF) shl 16) or
        ((buf[off + 3].toInt() and 0xFF) shl 24)
}
