package com.trevornk.ramblr

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure PCM conversion helpers for [AudioFileDecoder] (#285): any decoder output (mono/stereo/
 * multichannel, any sample rate, 16-bit or float) to the one format every Ramblr recognizer wants,
 * 16 kHz mono PCM16 little-endian. Kept free of Android types so it is unit-tested on the JVM.
 */
class PcmNormalizer(
    private val inRate: Int,
    private val channels: Int,
    private val floatSamples: Boolean,
    private val outRate: Int = AudioChunkPlanner.SAMPLE_RATE,
    private val sink: (ShortArray, Int) -> Unit,
) {
    init {
        require(inRate > 0 && channels > 0 && outRate > 0)
    }

    private val ratio = inRate.toDouble() / outRate
    private var acc = 0.0
    private var binRemaining = ratio
    private val out = ShortArray(8192)
    private var outCount = 0

    /** Feeds [length] bytes (a whole number of frames; a trailing partial frame is ignored). */
    fun feed(buf: ByteBuffer, length: Int) {
        val order = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * if (floatSamples) 4 else 2
        val frames = length / frameBytes
        var p = buf.position()
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                if (floatSamples) {
                    sum += buf.getFloat(p).toDouble()
                    p += 4
                } else {
                    sum += buf.getShort(p) / 32768.0
                    p += 2
                }
            }
            push(sum / channels)
        }
        buf.order(order)
    }

    /** Area-averaging resample: each output sample is the mean of the input span it covers, which
     *  doubles as a crude low-pass so 44.1/48 kHz speech does not alias on the way down. */
    private fun push(x: Double) {
        var w = 1.0
        while (w > 1e-12) {
            val take = minOf(w, binRemaining)
            acc += x * take
            w -= take
            binRemaining -= take
            if (binRemaining <= 1e-9) {
                emit(acc / ratio)
                acc = 0.0
                binRemaining = ratio
            }
        }
    }

    private fun emit(v: Double) {
        val s = (v * 32768.0).toInt().coerceIn(-32768, 32767)
        out[outCount++] = s.toShort()
        if (outCount == out.size) flushOut()
    }

    private fun flushOut() {
        if (outCount > 0) sink(out, outCount)
        outCount = 0
    }

    /** Emits the final partial bin (if meaningful) and any buffered output. */
    fun finish() {
        val covered = ratio - binRemaining
        if (covered > 0.5 * ratio) emit(acc / covered)
        flushOut()
    }

    companion object {
        fun shortsToBytes(samples: ShortArray, count: Int): ByteArray {
            val b = ByteArray(count * 2)
            for (i in 0 until count) {
                b[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                b[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
            }
            return b
        }
    }
}
