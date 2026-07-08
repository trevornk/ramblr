package com.trevornk.ramblr

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PcmFileBufferTest {

    private fun tempFile(): File = File.createTempFile("pcmbuffertest_", ".pcm").apply { deleteOnExit() }

    @Test fun `writes bytes to the backing file and tracks size`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 1000)
        val chunk = byteArrayOf(1, 2, 3, 4)

        assertTrue(buffer.write(chunk, 0, chunk.size))
        assertEquals(4L, buffer.bytesWritten)
        buffer.close()

        assertArrayEquals(chunk, file.readBytes())
    }

    @Test fun `accumulates across multiple writes`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 1000)

        buffer.write(byteArrayOf(1, 2), 0, 2)
        buffer.write(byteArrayOf(3, 4, 5), 0, 3)
        buffer.close()

        assertEquals(5L, buffer.bytesWritten)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), file.readBytes())
    }

    @Test fun `refuses writes that would exceed the cap and writes nothing`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 4)

        assertTrue(buffer.write(byteArrayOf(1, 2, 3, 4), 0, 4))
        assertFalse(buffer.write(byteArrayOf(5), 0, 1))
        buffer.close()

        assertEquals(4L, buffer.bytesWritten)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
    }

    @Test fun `writes the fitting even prefix when a chunk only partly fits, then reports the cap (L13)`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 6)

        assertTrue(buffer.write(byteArrayOf(1, 2), 0, 2))
        // 4 bytes of room left; a 6-byte chunk only partly fits -> writes the first 4, returns false.
        assertFalse(buffer.write(byteArrayOf(3, 4, 5, 6, 7, 8), 0, 6))
        buffer.close()

        assertEquals(6L, buffer.bytesWritten)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), file.readBytes())
    }

    @Test fun `partial write floors to an even byte count so a 16-bit sample is never split (L13)`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 5)

        assertTrue(buffer.write(byteArrayOf(1, 2), 0, 2))
        // 3 bytes of room left, but writing 3 would split a sample -> only the even 2 are written.
        assertFalse(buffer.write(byteArrayOf(3, 4, 5, 6), 0, 4))
        buffer.close()

        assertEquals(4L, buffer.bytesWritten)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
    }

    @Test fun `allows a write landing exactly on the cap`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 4)

        assertTrue(buffer.write(byteArrayOf(1, 2, 3, 4), 0, 4))
        buffer.close()

        assertEquals(4L, buffer.bytesWritten)
    }

    @Test fun `deleteFile removes the backing file`() {
        val file = tempFile()
        val buffer = PcmFileBuffer(file, maxBytes = 1000)
        buffer.write(byteArrayOf(1), 0, 1)
        buffer.close()

        assertTrue(file.exists())
        buffer.deleteFile()
        assertFalse(file.exists())
    }

    // -- readAsFloatArray --

    private fun writePcm16(file: File, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        file.writeBytes(bytes)
    }

    @Test fun `readAsFloatArray converts an empty file to an empty array`() {
        val file = tempFile()
        assertArrayEquals(FloatArray(0), PcmFileBuffer.readAsFloatArray(file), 0f)
    }

    @Test fun `readAsFloatArray converts known 16-bit samples to normalized floats`() {
        val file = tempFile()
        writePcm16(file, shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, -1))

        val samples = PcmFileBuffer.readAsFloatArray(file)

        assertEquals(4, samples.size)
        assertEquals(0f, samples[0], 1e-6f)
        assertEquals(Short.MAX_VALUE.toFloat() / 32768f, samples[1], 1e-6f)
        assertEquals(Short.MIN_VALUE.toFloat() / 32768f, samples[2], 1e-6f)
        assertEquals(-1f / 32768f, samples[3], 1e-6f)
    }

    @Test fun `readAsFloatArray handles a file larger than one internal chunk`() {
        val file = tempFile()
        // One 16-bit sample per index, cycling through a range of values, spanning several
        // internal 64KB read chunks (CHUNK_BYTES / 2 samples per chunk).
        val count = 100_000
        val samples = ShortArray(count) { (it % 30000).toShort() }
        writePcm16(file, samples)

        val result = PcmFileBuffer.readAsFloatArray(file)

        assertEquals(count, result.size)
        for (i in samples.indices step 4999) {
            assertEquals(samples[i].toFloat() / 32768f, result[i], 1e-6f)
        }
    }

    @Test fun `readAsFloatArray truncates a trailing odd byte`() {
        val file = tempFile()
        writePcm16(file, shortArrayOf(42))
        file.appendBytes(byteArrayOf(7)) // dangling half-sample

        val result = PcmFileBuffer.readAsFloatArray(file)

        assertEquals(1, result.size)
        assertEquals(42f / 32768f, result[0], 1e-6f)
    }

    // -- bytesToFloatArray (streaming chunk conversion, #29) --

    private fun pcm16Bytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[i * 2] = (s.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    @Test fun `bytesToFloatArray converts known 16-bit samples the same way as readAsFloatArray`() {
        val bytes = pcm16Bytes(shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, -1))

        val samples = PcmFileBuffer.bytesToFloatArray(bytes, bytes.size)

        assertEquals(4, samples.size)
        assertEquals(0f, samples[0], 1e-6f)
        assertEquals(Short.MAX_VALUE.toFloat() / 32768f, samples[1], 1e-6f)
        assertEquals(Short.MIN_VALUE.toFloat() / 32768f, samples[2], 1e-6f)
        assertEquals(-1f / 32768f, samples[3], 1e-6f)
    }

    @Test fun `bytesToFloatArray only converts the first len bytes, ignoring the rest of a reused buffer`() {
        // Mirrors how RecordingEngine calls it: a fixed-size buffer reused across reads, only
        // the first `n` bytes of which are valid for this particular chunk.
        val bytes = pcm16Bytes(shortArrayOf(100, 200, 300, 400))

        val samples = PcmFileBuffer.bytesToFloatArray(bytes, 4) // first 2 samples only

        assertEquals(2, samples.size)
        assertEquals(100f / 32768f, samples[0], 1e-6f)
        assertEquals(200f / 32768f, samples[1], 1e-6f)
    }

    @Test fun `bytesToFloatArray truncates a trailing odd byte`() {
        val bytes = pcm16Bytes(shortArrayOf(42)) + byteArrayOf(7)

        val samples = PcmFileBuffer.bytesToFloatArray(bytes, bytes.size)

        assertEquals(1, samples.size)
        assertEquals(42f / 32768f, samples[0], 1e-6f)
    }

    @Test fun `bytesToFloatArray of zero length returns an empty array`() {
        assertArrayEquals(FloatArray(0), PcmFileBuffer.bytesToFloatArray(ByteArray(0), 0), 0f)
    }
}
