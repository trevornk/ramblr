package com.trevornk.ramblr

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * #285: splits a long 16 kHz mono PCM16 recording into chunks small enough for one transcription
 * request, cutting in the quietest nearby spot so a word is rarely sliced in half.
 *
 * Why chunk at all: a cloud request has a hard upload ceiling (OpenAI-compatible endpoints reject
 * bodies over ~25 MB; [GeminiTranscriberClient.MAX_INLINE_PCM_BYTES] caps inline audio at 10 MB),
 * and the local recognizers are memory-bound (#132). A 2 hour file is ~230 MB of PCM; it can never
 * be one request or one decode. Chunks are planned in samples (not bytes or ms) so every boundary
 * is sample-aligned by construction.
 */
object AudioChunkPlanner {
    const val SAMPLE_RATE = 16_000

    /** Energy-scan resolution: 100 ms. Cuts are placed on frame boundaries. */
    const val FRAME_SAMPLES = 1_600

    /** A final chunk shorter than this is never emitted on its own (it would be a sub-second
     *  request that most providers reject or hallucinate on); the previous cut moves earlier. */
    const val MIN_TAIL_SAMPLES = 2L * SAMPLE_RATE

    /** How far back from the hard limit a quiet cut point is looked for. */
    const val DEFAULT_SEARCH_BACK_SAMPLES = 15L * SAMPLE_RATE

    data class Chunk(val index: Int, val startSample: Long, val endSample: Long) {
        val length: Long get() = endSample - startSample
        val startByte: Long get() = startSample * 2
        val lengthBytes: Long get() = length * 2
    }

    /**
     * Plans chunks over [totalSamples], none longer than [maxChunkSamples]. [frameEnergies] (one
     * value per [FRAME_SAMPLES] frame, see [scanEnergies]) is optional: without it cuts land at
     * the hard limit. With it, each cut moves to the quietest frame within [searchBackSamples]
     * before the limit (latest wins a tie, so chunks stay as full as possible).
     */
    fun plan(
        totalSamples: Long,
        maxChunkSamples: Long,
        frameEnergies: FloatArray? = null,
        searchBackSamples: Long = DEFAULT_SEARCH_BACK_SAMPLES,
    ): List<Chunk> {
        require(maxChunkSamples > MIN_TAIL_SAMPLES) { "maxChunkSamples too small" }
        if (totalSamples <= 0L) return emptyList()
        val out = mutableListOf<Chunk>()
        var pos = 0L
        while (pos < totalSamples) {
            if (totalSamples - pos <= maxChunkSamples) {
                out += Chunk(out.size, pos, totalSamples)
                break
            }
            var cut = pos + maxChunkSamples
            if (frameEnergies != null) cut = quietestCut(pos, cut, searchBackSamples, frameEnergies)
            // Never leave a sliver as the last chunk: pull this cut earlier instead.
            if (totalSamples - cut < MIN_TAIL_SAMPLES) cut = totalSamples - MIN_TAIL_SAMPLES
            out += Chunk(out.size, pos, cut)
            pos = cut
        }
        return out
    }

    private fun quietestCut(pos: Long, limit: Long, searchBack: Long, energies: FloatArray): Long {
        // Candidate cut points are frame boundaries in (floor, limit]. The floor keeps every chunk
        // at least half full so a noisy stretch can't degrade into tiny chunks.
        val floor = maxOf(pos + (limit - pos) / 2, limit - searchBack)
        val firstFrame = ((floor + FRAME_SAMPLES - 1) / FRAME_SAMPLES).toInt()
        val lastFrame = (limit / FRAME_SAMPLES).toInt() // boundary index: cut = frame * FRAME_SAMPLES
        var best = -1
        var bestEnergy = Float.MAX_VALUE
        for (boundary in firstFrame..lastFrame) {
            // A boundary sits between frame (boundary-1) and frame (boundary); judge the quiet of
            // the 200 ms around it. Boundaries past the scanned range are unknown: skip them.
            val before = energies.getOrNull(boundary - 1) ?: continue
            val after = energies.getOrNull(boundary) ?: before
            val e = before + after
            if (e <= bestEnergy) { // <=: the latest quiet spot wins a tie
                bestEnergy = e
                best = boundary
            }
        }
        return if (best < 0) limit else best.toLong() * FRAME_SAMPLES
    }

    /** RMS energy of each [FRAME_SAMPLES] frame of a PCM16 LE [file]. Streams; O(1) memory. */
    fun scanEnergies(file: File): FloatArray {
        val frames = ((file.length() / 2) / FRAME_SAMPLES).toInt() + 1
        val out = FloatArray(frames)
        val buf = ByteArray(FRAME_SAMPLES * 2)
        BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
            var f = 0
            while (f < frames) {
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < 2) break
                out[f++] = rms(buf, read / 2)
            }
        }
        return out
    }

    internal fun rms(buf: ByteArray, samples: Int): Float {
        if (samples <= 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = ((buf[i * 2 + 1].toInt() shl 8) or (buf[i * 2].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
        }
        return Math.sqrt(sum / samples).toFloat()
    }

    /** Copies [chunk]'s samples out of [source] into [dest] (PCM16 LE, headerless). */
    fun extract(source: File, chunk: Chunk, dest: File) {
        FileInputStream(source).use { input ->
            var toSkip = chunk.startByte
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) throw java.io.EOFException("source shorter than chunk start")
                toSkip -= skipped
            }
            dest.outputStream().buffered(64 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = chunk.lengthBytes
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw java.io.EOFException("source shorter than chunk end")
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }
}

/**
 * Splits a transcript into pieces of at most [maxChars] for the cleanup step (#285). A multi-hour
 * transcript is far past any cleanup model's context window, so it is cleaned piece by piece. Cuts
 * prefer a paragraph break, then a sentence end, then a space, so a piece is a whole thought.
 */
object TranscriptChunker {
    const val DEFAULT_MAX_CHARS = 3_000

    fun split(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 100) { "maxChars too small" }
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= maxChars) return listOf(t)
        val out = mutableListOf<String>()
        var rest = t
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val minCut = maxChars / 2
            var cut = window.lastIndexOf("\n\n").takeIf { it >= minCut } ?: -1
            if (cut < 0) cut = lastSentenceEnd(window).takeIf { it >= minCut } ?: -1
            if (cut < 0) cut = window.lastIndexOf(' ').takeIf { it >= minCut } ?: -1
            if (cut < 0) cut = maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }

    /** Index just past the last sentence terminator followed by whitespace, or -1. */
    private fun lastSentenceEnd(s: String): Int {
        for (i in s.length - 2 downTo 0) {
            if (s[i] in ".!?" && s[i + 1].isWhitespace()) return i + 1
        }
        return -1
    }
}
