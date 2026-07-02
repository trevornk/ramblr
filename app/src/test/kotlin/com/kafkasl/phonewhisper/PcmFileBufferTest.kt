package com.kafkasl.phonewhisper

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
}
