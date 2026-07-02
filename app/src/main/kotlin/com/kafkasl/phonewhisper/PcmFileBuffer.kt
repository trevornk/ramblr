package com.kafkasl.phonewhisper

import java.io.File
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

    /** Writes [len] bytes from [buf] starting at [offset]. Returns false if the cap was hit (nothing is written). */
    fun write(buf: ByteArray, offset: Int, len: Int): Boolean {
        if (bytesWritten + len > maxBytes) return false
        out.write(buf, offset, len)
        bytesWritten += len
        return true
    }

    override fun close() { out.close() }

    fun deleteFile() { file.delete() }
}
