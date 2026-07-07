package com.trevornk.ramblr

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * [PcmWavRequestBody] is the file-backed replacement for handing OkHttp one giant WAV
 * ByteArray (see #16) -- it must produce byte-for-byte the same request body a caller would
 * get from `WavWriter.encode(pcmBytes).toRequestBody(...)`, just without ever holding the
 * whole thing in one array.
 */
class PcmWavRequestBodyTest {

    private fun tempPcmFile(bytes: ByteArray): File =
        File.createTempFile("pcmwavbodytest_", ".pcm").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    @Test fun `content length is header size plus pcm file size`() {
        val file = tempPcmFile(ByteArray(1000))
        val body = PcmWavRequestBody(file)
        assertEquals(44L + 1000L, body.contentLength())
    }

    @Test fun `content type is audio wav`() {
        val body = PcmWavRequestBody(tempPcmFile(ByteArray(0)))
        assertEquals("audio", body.contentType()?.type)
        assertEquals("wav", body.contentType()?.subtype)
    }

    @Test fun `writeTo streams a header followed by the exact pcm bytes`() {
        val pcm = ByteArray(4000) { (it % 256).toByte() }
        val file = tempPcmFile(pcm)
        val body = PcmWavRequestBody(file)

        val sink = Buffer()
        body.writeTo(sink)
        val written = sink.readByteArray()

        assertEquals(44 + pcm.size, written.size)
        assertArrayEquals(WavWriter.header(pcm.size.toLong()), written.copyOfRange(0, 44))
        assertArrayEquals(pcm, written.copyOfRange(44, written.size))
    }

    @Test fun `writeTo matches WavWriter encode byte-for-byte`() {
        val pcm = ByteArray(12345) { (it * 7 % 256).toByte() }
        val file = tempPcmFile(pcm)
        val body = PcmWavRequestBody(file)

        val sink = Buffer()
        body.writeTo(sink)

        assertArrayEquals(WavWriter.encode(pcm), sink.readByteArray())
    }

    @Test fun `handles an empty pcm file`() {
        val body = PcmWavRequestBody(tempPcmFile(ByteArray(0)))
        val sink = Buffer()
        body.writeTo(sink)
        assertEquals(44, sink.readByteArray().size)
    }
}
