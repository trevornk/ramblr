package com.trevornk.ramblr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes a user-chosen audio file (any container/codec the platform's MediaExtractor + MediaCodec
 * can read: m4a/aac, mp3, ogg/opus, wav, flac, amr, ...) to a headerless 16 kHz mono PCM16 file,
 * streaming: memory stays flat however long the file is (#285).
 *
 * Reads through a content [Uri] (SAF picker / share-intent grant), so no storage permission is
 * needed. Runs on the caller's thread and blocks; [isCancelled] is polled between buffers.
 */
object AudioFileDecoder {
    private const val TAG = "AudioFileDecoder"
    private const val TIMEOUT_US = 10_000L
    private const val STALL_LIMIT = 3_000 // ~30 s of consecutive empty dequeues => give up

    data class Result(val pcmBytes: Long, val durationMs: Long)

    fun decode(
        context: Context,
        uri: Uri,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (fraction: Float) -> Unit = {},
    ): Result = decode({ it.setDataSource(context, uri, null) }, dest, isCancelled, onProgress)

    /** Decodes an app-private [source] file (an imported copy or a saved recording). */
    fun decode(
        source: File,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (fraction: Float) -> Unit = {},
    ): Result = decode({ it.setDataSource(source.absolutePath) }, dest, isCancelled, onProgress)

    private fun decode(
        openSource: (MediaExtractor) -> Unit,
        dest: File,
        isCancelled: () -> Boolean,
        onProgress: (fraction: Float) -> Unit,
    ): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                openSource(extractor)
            } catch (e: Exception) {
                throw AudioDecodeException("Couldn't open the audio file", e)
            }
            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    inFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inFormat == null) throw AudioDecodeException("No audio track found in this file")
            extractor.selectTrack(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            codec = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(inFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw AudioDecodeException("This audio format ($mime) isn't supported on this device", e)
            }

            var rate = inFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
            var channels = inFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var floatPcm = false
            var normalizer: PcmNormalizer? = null
            var totalBytes = 0L
            var lastProgress = -1f

            BufferedOutputStream(FileOutputStream(dest), 256 * 1024).use { out ->
                fun newNormalizer() = PcmNormalizer(rate, channels, floatPcm) { s, n ->
                    val bytes = PcmNormalizer.shortsToBytes(s, n)
                    out.write(bytes)
                    totalBytes += bytes.size
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var stalls = 0
                while (!outputDone) {
                    if (isCancelled()) throw AudioDecodeException("Cancelled")
                    var progressed = false
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            progressed = true
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            normalizer?.finish()
                            rate = f.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate)
                            channels = f.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            floatPcm = f.intOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                            normalizer = newNormalizer()
                            progressed = true
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val ob = codec.getOutputBuffer(outIdx)!!
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val norm = normalizer ?: newNormalizer().also { normalizer = it }
                                norm.feed(ob, info.size)
                                if (durationUs > 0) {
                                    val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                    if (p - lastProgress >= 0.01f) { lastProgress = p; onProgress(p) }
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            progressed = true
                        }
                    }
                    if (progressed) stalls = 0 else if (++stalls > STALL_LIMIT) {
                        throw AudioDecodeException("Decoder stopped responding")
                    }
                }
                normalizer?.finish()
            }
            if (totalBytes == 0L) throw AudioDecodeException("The file decoded to no audio")
            return Result(totalBytes, totalBytes / 32L)
        } catch (e: AudioDecodeException) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            Log.w(TAG, "Decode failed", e)
            throw AudioDecodeException("Couldn't decode this audio file", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.intOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}
