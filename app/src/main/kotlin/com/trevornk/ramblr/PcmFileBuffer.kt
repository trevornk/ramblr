package com.trevornk.ramblr

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Streams PCM chunks straight to a temp file instead of an in-memory buffer, so heap stays flat
 * for the duration of a recording. [maxBytes] is a hard backstop independent of any duration
 * timer — `write` refuses once the cap would be exceeded instead of growing without bound.
 */
class PcmFileBuffer(private val file: File, private val maxBytes: Long) : AutoCloseable {
    private val out = FileOutputStream(file)

    var bytesWritten: Long = 0
        private set

    /**
     * Writes [len] bytes from [buf] starting at [offset]. Returns true if the whole chunk fit, or
     * false once the cap is reached. When only part of the chunk fits, that fitting prefix is still
     * written (floored to an even byte count so a 16-bit sample is never split) before returning
     * false — previously the entire final chunk was dropped, losing up to ~50ms of audio right at
     * the 10-minute duration cap (L13).
     */
    fun write(buf: ByteArray, offset: Int, len: Int): Boolean {
        if (bytesWritten + len > maxBytes) {
            val remaining = (maxBytes - bytesWritten).coerceAtLeast(0L).toInt()
            val fits = minOf(len, remaining).let { it - (it % 2) }
            if (fits > 0) {
                out.write(buf, offset, fits)
                bytesWritten += fits
            }
            return false
        }
        out.write(buf, offset, len)
        bytesWritten += len
        return true
    }

    override fun close() { out.close() }

    fun deleteFile() { file.delete() }

    companion object {
        // Kept even so a chunk never splits a 16-bit sample across two reads.
        private const val CHUNK_BYTES = 64 * 1024

        /**
         * Converts a 16-bit little-endian PCM file to a [FloatArray] of samples in [-1, 1].
         * Reads the file in bounded chunks instead of loading it into a ByteArray first, so the
         * only large allocation is the output array itself (still one bounded-size allocation —
         * #16's duration cap keeps it to at most ~38MB for a 10-minute recording).
         */
        fun readAsFloatArray(file: File): FloatArray {
            val sampleCount = (file.length() / 2).toInt()
            val samples = FloatArray(sampleCount)
            val chunk = ByteArray(CHUNK_BYTES)
            var sampleIndex = 0

            BufferedInputStream(FileInputStream(file)).use { input ->
                while (sampleIndex < sampleCount) {
                    val wanted = minOf(chunk.size, (sampleCount - sampleIndex) * 2)
                    var read = 0
                    while (read < wanted) {
                        val n = input.read(chunk, read, wanted - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break

                    var i = 0
                    while (i + 1 < read) {
                        val lo = chunk[i].toInt() and 0xFF
                        val hi = chunk[i + 1].toInt()
                        samples[sampleIndex++] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                        i += 2
                    }
                }
            }
            return samples
        }

        /**
         * Converts [len] bytes of 16-bit little-endian PCM at the start of [buf] into a
         * [FloatArray] of samples in [-1, 1] -- the same conversion as [readAsFloatArray], but for
         * one small chunk at a time instead of a whole file. Used by the streaming live-preview
         * path (#29) to feed each [android.media.AudioRecord.read] chunk to the streaming
         * recognizer as it arrives, without waiting for the recording to finish.
         */
        fun bytesToFloatArray(buf: ByteArray, len: Int): FloatArray {
            val sampleCount = len / 2
            val samples = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val lo = buf[i * 2].toInt() and 0xFF
                val hi = buf[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
            }
            return samples
        }
    }
}
