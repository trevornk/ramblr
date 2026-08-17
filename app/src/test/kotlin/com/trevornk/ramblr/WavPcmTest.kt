package com.trevornk.ramblr

import com.trevornk.ramblr.tools.WavPcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.ByteArrayOutputStream

/**
 * Tests for the RIFF chunk walker. Every WAV byte sequence here is generated programmatically --
 * no audio assets are checked in. The `LIST chunk before data` case is the mutation target: a
 * naive 44-byte header skip produces the wrong payload for it and must fail these assertions.
 */
class WavPcmTest {

    @get:Rule val temp = TemporaryFolder()

    // ------------------------------------------------------------ byte-level WAV builders

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun fmtChunk(
        audioFormat: Int = 1,
        channels: Int = 1,
        sampleRate: Int = 16000,
        bitsPerSample: Int = 16,
        extraFmtBytes: ByteArray = ByteArray(0),
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val body = ByteArrayOutputStream()
        body.write(le16(audioFormat))
        body.write(le16(channels))
        body.write(le32(sampleRate))
        body.write(le32(byteRate))
        body.write(le16(blockAlign))
        body.write(le16(bitsPerSample))
        body.write(extraFmtBytes)
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ascii("fmt "))
        out.write(le32(bodyBytes.size))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ascii(id))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0) // RIFF word-alignment pad byte
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(ascii("WAVE"))
        chunks.forEach { body.write(it) }
        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ascii("RIFF"))
        out.write(le32(bodyBytes.size))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    /** Deterministic pseudo-PCM: [sampleCount] signed 16-bit LE samples with recognisable values. */
    private fun pcm(sampleCount: Int, seed: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 0 until sampleCount) {
            val v = ((i * 37 + seed) % 30000) - 15000
            out.write(le16(v and 0xFFFF))
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ happy paths

    @Test fun `extracts pcm from a canonical 44-byte-header wav`() {
        val body = pcm(64)
        val wav = riff(fmtChunk(), chunk("data", body))
        assertEquals("canonical WAV should be exactly 44 bytes of header", 44 + body.size, wav.size)
        assertArrayEquals(body, WavPcm.extractPcm(wav))
    }

    /**
     * The naive-44-byte-skip killer. A `LIST`/`INFO` metadata chunk sits between `fmt ` and
     * `data`, so the PCM begins well past byte 44. A fixed skip would return metadata bytes plus
     * a truncated tail; only a real chunk walk returns the exact payload.
     */
    @Test fun `extracts pcm when an extra LIST chunk precedes the data chunk`() {
        val body = pcm(128, seed = 7)
        val listPayload = ascii("INFOISFT") + le32(14) + ascii("Ramblr eval 1\u0000")
        val wav = riff(fmtChunk(), chunk("LIST", listPayload), chunk("data", body))

        assertTrue("data must start past byte 44 for this test to be meaningful", wav.size - body.size > 44)
        val extracted = WavPcm.extractPcm(wav)
        assertEquals(body.size, extracted.size)
        assertArrayEquals(body, extracted)
    }

    @Test fun `extracts pcm when several chunks precede data and one has an odd length`() {
        val body = pcm(32, seed = 3)
        val wav = riff(
            fmtChunk(),
            chunk("fact", le32(32)),
            chunk("junk", ascii("odd-length-metadata")), // 19 bytes -> pad byte exercised
            chunk("data", body),
        )
        assertArrayEquals(body, WavPcm.extractPcm(wav))
    }

    @Test fun `tolerates a fmt chunk with extension bytes`() {
        val body = pcm(16)
        val wav = riff(fmtChunk(extraFmtBytes = le16(0)), chunk("data", body))
        assertArrayEquals(body, WavPcm.extractPcm(wav))
    }

    @Test fun `ignores trailing chunks after data`() {
        val body = pcm(16, seed = 11)
        val wav = riff(fmtChunk(), chunk("data", body), chunk("LIST", ascii("INFOtrailer")))
        assertArrayEquals(body, WavPcm.extractPcm(wav))
    }

    @Test fun `parses the format header fields`() {
        val wav = riff(fmtChunk(), chunk("data", pcm(8)))
        val format = WavPcm.readFormat(wav)
        assertEquals(1, format.audioFormat)
        assertEquals(1, format.channels)
        assertEquals(16000, format.sampleRate)
        assertEquals(16, format.bitsPerSample)
    }

    @Test fun `writes raw pcm to a temp file that matches the data chunk byte for byte`() {
        val body = pcm(100, seed = 5)
        val wav = riff(fmtChunk(), chunk("LIST", ascii("INFOxx")), chunk("data", body))
        val wavFile = temp.newFile("fixture.wav")
        wavFile.writeBytes(wav)

        val pcmFile = WavPcm.extractPcmToTempFile(wavFile)
        try {
            assertEquals(body.size.toLong(), pcmFile.length())
            assertArrayEquals(body, pcmFile.readBytes())
        } finally {
            pcmFile.delete()
        }
    }

    // ------------------------------------------------------------ rejection paths

    private fun expectReject(wav: ByteArray, expectedFragment: String) {
        val error = try {
            WavPcm.extractPcm(wav)
            null
        } catch (e: WavPcm.UnsupportedWavException) {
            e.message ?: ""
        }
        assertTrue(
            "expected an UnsupportedWavException mentioning '$expectedFragment', got: $error",
            error != null && error.contains(expectedFragment, ignoreCase = true),
        )
    }

    @Test fun `rejects a non-PCM compressed format`() {
        expectReject(riff(fmtChunk(audioFormat = 3), chunk("data", pcm(8))), "PCM")
    }

    @Test fun `rejects the wrong sample rate`() {
        expectReject(riff(fmtChunk(sampleRate = 44100), chunk("data", pcm(8))), "44100")
    }

    @Test fun `rejects stereo audio`() {
        expectReject(riff(fmtChunk(channels = 2), chunk("data", pcm(8))), "channel")
    }

    @Test fun `rejects 8-bit and 24-bit depths`() {
        expectReject(riff(fmtChunk(bitsPerSample = 8), chunk("data", pcm(8))), "bit")
        expectReject(riff(fmtChunk(bitsPerSample = 24), chunk("data", pcm(8))), "bit")
    }

    @Test fun `rejects a file that is not RIFF`() {
        val notRiff = ascii("OggS") + ByteArray(60)
        expectReject(notRiff, "RIFF")
    }

    @Test fun `rejects a RIFF file whose form type is not WAVE`() {
        val bytes = ascii("RIFF") + le32(40) + ascii("AVI ") + ByteArray(36)
        expectReject(bytes, "WAVE")
    }

    @Test fun `rejects a wav with no fmt chunk`() {
        expectReject(riff(chunk("data", pcm(8))), "fmt")
    }

    @Test fun `rejects a wav with no data chunk`() {
        expectReject(riff(fmtChunk(), chunk("LIST", ascii("INFOnope"))), "data")
    }

    @Test fun `rejects an empty data chunk`() {
        expectReject(riff(fmtChunk(), chunk("data", ByteArray(0))), "empty")
    }

    @Test fun `rejects a truncated file`() {
        expectReject(ascii("RIFF") + le32(4), "truncat")
    }

    @Test fun `rejects a chunk header that declares a length running past the end of file`() {
        val out = ByteArrayOutputStream()
        out.write(ascii("WAVE"))
        out.write(fmtChunk())
        out.write(ascii("data"))
        out.write(le32(1_000_000)) // lies about its size
        out.write(pcm(4))
        val body = out.toByteArray()
        val wav = ascii("RIFF") + le32(body.size) + body
        expectReject(wav, "truncat")
    }

    @Test fun `rejects a negative or absurd chunk length`() {
        val out = ByteArrayOutputStream()
        out.write(ascii("WAVE"))
        out.write(ascii("junk"))
        out.write(le32(-16)) // reads as a huge unsigned value
        out.write(ByteArray(8))
        val body = out.toByteArray()
        expectReject(ascii("RIFF") + le32(body.size) + body, "truncat")
    }

    @Test fun `rejects an odd-sized data chunk that cannot hold whole 16-bit samples`() {
        expectReject(riff(fmtChunk(), chunk("data", ByteArray(7))), "sample")
    }
}
