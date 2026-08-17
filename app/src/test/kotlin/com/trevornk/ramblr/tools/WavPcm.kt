package com.trevornk.ramblr.tools

import java.io.File

/**
 * Minimal RIFF/WAVE reader for the #129 transcription benchmark.
 *
 * Exists because [com.trevornk.ramblr.GeminiTranscriberClient.transcribe] consumes a **raw PCM**
 * file and wraps it in a WAV header itself; handing it a `.wav` would produce a double-headered
 * payload with 44 bytes of garbage at the front of the audio. The benchmark fixtures are stored
 * as `.wav` (self-describing, playable, verifiable), so they have to be unwrapped first.
 *
 * The `data` chunk is located by **walking the chunk headers**, not by assuming the canonical
 * 44-byte layout. Real-world WAV writers routinely emit `LIST`/`INFO`, `fact`, or `junk` chunks
 * between `fmt ` and `data`; a fixed 44-byte skip silently yields metadata bytes interpreted as
 * audio, which would show up as a mysteriously terrible WER rather than as an error.
 *
 * Only the exact format the app records is accepted (16 kHz, mono, signed 16-bit little-endian
 * PCM) — anything else is rejected loudly rather than resampled, because a silently resampled
 * fixture would make the benchmark measure the resampler instead of the model.
 */
object WavPcm {

    const val REQUIRED_SAMPLE_RATE = 16_000
    const val REQUIRED_CHANNELS = 1
    const val REQUIRED_BITS_PER_SAMPLE = 16
    private const val WAVE_FORMAT_PCM = 1

    /** Structured failure: a file that isn't a WAV, is truncated, or isn't the required format. */
    class UnsupportedWavException(message: String) : Exception(message)

    data class Format(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun require(condition: Boolean, message: () -> String) {
        if (!condition) throw UnsupportedWavException(message())
    }

    /** One located chunk: its 4-char id and the [start, end) range of its payload in the file. */
    private data class Chunk(val id: String, val payloadStart: Int, val payloadEnd: Int)

    /**
     * Walks the RIFF chunk list from byte 12 (immediately after `RIFF<size>WAVE`), honouring each
     * chunk's declared size and RIFF's word-alignment pad byte for odd-sized payloads.
     */
    private fun walkChunks(bytes: ByteArray): List<Chunk> {
        require(bytes.size >= 12) { "File is truncated: ${bytes.size} bytes, need at least a 12-byte RIFF header" }
        require(ascii(bytes, 0, 4) == "RIFF") { "Not a RIFF file: leading bytes are '${ascii(bytes, 0, 4)}'" }
        require(ascii(bytes, 8, 4) == "WAVE") { "RIFF form type is '${ascii(bytes, 8, 4)}', expected 'WAVE'" }

        val chunks = mutableListOf<Chunk>()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val declared = u32(bytes, offset + 4)
            val payloadStart = offset + 8
            require(declared >= 0 && payloadStart + declared <= bytes.size.toLong()) {
                "Chunk '$id' declares $declared payload bytes at offset $payloadStart but the file is " +
                    "truncated at ${bytes.size} bytes"
            }
            val payloadEnd = (payloadStart + declared).toInt()
            chunks.add(Chunk(id, payloadStart, payloadEnd))
            // RIFF pads odd-sized payloads to an even boundary; the pad byte is not part of the payload.
            offset = payloadEnd + (declared % 2).toInt()
        }
        require(chunks.isNotEmpty()) { "File is truncated: no RIFF chunks after the WAVE header" }
        return chunks
    }

    private fun formatOf(bytes: ByteArray, chunks: List<Chunk>): Format {
        val fmt = chunks.firstOrNull { it.id == "fmt " }
            ?: throw UnsupportedWavException("No 'fmt ' chunk found (chunks present: ${chunks.joinToString { "'${it.id}'" }})")
        require(fmt.payloadEnd - fmt.payloadStart >= 16) {
            "'fmt ' chunk is truncated: ${fmt.payloadEnd - fmt.payloadStart} bytes, need at least 16"
        }
        return Format(
            audioFormat = u16(bytes, fmt.payloadStart),
            channels = u16(bytes, fmt.payloadStart + 2),
            sampleRate = u32(bytes, fmt.payloadStart + 4).toInt(),
            bitsPerSample = u16(bytes, fmt.payloadStart + 14),
        )
    }

    /** Reads the `fmt ` chunk without extracting audio or enforcing the required format. */
    fun readFormat(bytes: ByteArray): Format = formatOf(bytes, walkChunks(bytes))

    private fun checkSupported(format: Format) {
        require(format.audioFormat == WAVE_FORMAT_PCM) {
            "Unsupported WAV encoding: audioFormat=${format.audioFormat}, only uncompressed PCM (1) is supported"
        }
        require(format.sampleRate == REQUIRED_SAMPLE_RATE) {
            "Unsupported sample rate ${format.sampleRate} Hz, benchmark fixtures must be $REQUIRED_SAMPLE_RATE Hz"
        }
        require(format.channels == REQUIRED_CHANNELS) {
            "Unsupported channel count ${format.channels}, benchmark fixtures must be mono ($REQUIRED_CHANNELS channel)"
        }
        require(format.bitsPerSample == REQUIRED_BITS_PER_SAMPLE) {
            "Unsupported bit depth ${format.bitsPerSample}-bit, benchmark fixtures must be $REQUIRED_BITS_PER_SAMPLE-bit"
        }
    }

    /**
     * Returns the raw signed-16-bit-LE PCM payload of the `data` chunk. Throws
     * [UnsupportedWavException] for anything that isn't 16 kHz mono 16-bit PCM, or whose chunk
     * structure is malformed.
     */
    fun extractPcm(bytes: ByteArray): ByteArray {
        val chunks = walkChunks(bytes)
        checkSupported(formatOf(bytes, chunks))
        val data = chunks.firstOrNull { it.id == "data" }
            ?: throw UnsupportedWavException("No 'data' chunk found (chunks present: ${chunks.joinToString { "'${it.id}'" }})")
        val size = data.payloadEnd - data.payloadStart
        require(size > 0) { "'data' chunk is empty — the fixture contains no audio" }
        val frameBytes = REQUIRED_CHANNELS * REQUIRED_BITS_PER_SAMPLE / 8
        require(size % frameBytes == 0) {
            "'data' chunk is $size bytes, not a whole number of $frameBytes-byte 16-bit mono samples"
        }
        return bytes.copyOfRange(data.payloadStart, data.payloadEnd)
    }

    fun extractPcm(wavFile: File): ByteArray = try {
        extractPcm(wavFile.readBytes())
    } catch (e: UnsupportedWavException) {
        throw UnsupportedWavException("${wavFile.path}: ${e.message}")
    }

    /**
     * Extracts [wavFile]'s PCM payload to a fresh temp file, which is what
     * [com.trevornk.ramblr.GeminiTranscriberClient.transcribe] expects as its `pcmFile`. The
     * caller owns the returned file and should delete it; it is also marked delete-on-exit so a
     * crashed benchmark run doesn't leave audio lying around indefinitely.
     */
    fun extractPcmToTempFile(wavFile: File): File {
        val pcm = extractPcm(wavFile)
        val out = File.createTempFile("ramblr-eval-", ".pcm")
        out.deleteOnExit()
        out.writeBytes(pcm)
        return out
    }
}
