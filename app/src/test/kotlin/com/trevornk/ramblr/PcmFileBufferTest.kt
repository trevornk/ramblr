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

    // -- forEachChunk (streaming file read for segmented decode, #132) --

    @Test fun `forEachChunk streams a file in bounded batches that reassemble to the whole file`() {
        val file = tempFile()
        val count = 100_000
        val samples = ShortArray(count) { (it % 30000).toShort() }
        writePcm16(file, samples)

        val batches = mutableListOf<Int>()
        val collected = mutableListOf<Float>()
        PcmFileBuffer.forEachChunk(file, chunkSamples = 16000) { chunk ->
            batches += chunk.size
            collected += chunk.toList()
        }

        // Every batch is bounded by chunkSamples -- that bound is the whole point of this API.
        assertTrue(batches.all { it <= 16000 })
        assertEquals(count, collected.size)
        assertEquals(PcmFileBuffer.readAsFloatArray(file).toList(), collected)
    }

    @Test fun `forEachChunk emits nothing for an empty file`() {
        val file = tempFile()
        var calls = 0
        PcmFileBuffer.forEachChunk(file, chunkSamples = 16000) { calls++ }
        assertEquals(0, calls)
    }

    @Test fun `forEachChunk handles a file smaller than one chunk in a single batch`() {
        val file = tempFile()
        writePcm16(file, shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, -1))

        val batches = mutableListOf<FloatArray>()
        PcmFileBuffer.forEachChunk(file, chunkSamples = 16000) { batches += it }

        assertEquals(1, batches.size)
        assertEquals(4, batches[0].size)
        assertEquals(Short.MAX_VALUE.toFloat() / 32768f, batches[0][1], 1e-6f)
    }

    @Test fun `forEachChunk truncates a trailing odd byte`() {
        val file = tempFile()
        writePcm16(file, shortArrayOf(42))
        file.appendBytes(byteArrayOf(7))

        val collected = mutableListOf<Float>()
        PcmFileBuffer.forEachChunk(file, chunkSamples = 16000) { collected += it.toList() }

        assertEquals(1, collected.size)
        assertEquals(42f / 32768f, collected[0], 1e-6f)
    }

    @Test fun `forEachChunk hands out a fresh array per batch, not a reused buffer`() {
        // SpeechSegmenter may retain a slice of a chunk as carry-over, so reusing one array
        // across calls would silently corrupt the pending remainder.
        val file = tempFile()
        writePcm16(file, ShortArray(10) { it.toShort() })

        val batches = mutableListOf<FloatArray>()
        PcmFileBuffer.forEachChunk(file, chunkSamples = 2) { batches += it }

        assertTrue(batches.size > 1)
        assertNotSame(batches[0], batches[1])
    }

    @Test fun `forEachChunk rejects a non-positive chunk size`() {
        val file = tempFile()
        writePcm16(file, shortArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            PcmFileBuffer.forEachChunk(file, chunkSamples = 0) {}
        }
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
