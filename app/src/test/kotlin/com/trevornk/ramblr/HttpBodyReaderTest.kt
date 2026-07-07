package com.trevornk.ramblr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpBodyReaderTest {

    private fun responseWith(body: ResponseBody?): Response = Response.Builder()
        .request(Request.Builder().url("https://example.test/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .apply { body?.let { body(it) } }
        .build()

    /** A body whose source dies with [IOException] mid-read, like a socket stalling after the
     *  headers already arrived — the exact case that used to escape onResponse uncaught. */
    private fun throwingBody(): ResponseBody = object : ResponseBody() {
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = 100L
        override fun source(): BufferedSource = object : okio.ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                throw IOException("unexpected end of stream")
        }.buffer()
    }

    @Test
    fun `returns body text on success`() {
        val result = HttpBodyReader.read(responseWith("""{"ok":true}""".toResponseBody("application/json".toMediaType())))
        assertEquals("""{"ok":true}""", result.getOrNull())
    }

    @Test
    fun `returns empty string for an absent body`() {
        val result = HttpBodyReader.read(responseWith(null))
        assertEquals("", result.getOrNull())
    }

    @Test
    fun `returns failure instead of throwing when the body read dies mid-stream`() {
        val result = HttpBodyReader.read(responseWith(throwingBody()))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("unexpected end of stream", result.exceptionOrNull()?.message)
    }
}
