package com.kafkasl.phonewhisper

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

    private fun readInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        ((buf[off + 2].toInt() and 0xFF) shl 16) or
        ((buf[off + 3].toInt() and 0xFF) shl 24)
}
