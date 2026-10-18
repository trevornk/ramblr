package com.trevornk.ramblr

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns the recorder's raw 16-bit PCM chunks into a 0..1 loudness value for the keyboard's mic
 * animation. Keeps the loudest chunk seen in each [intervalMs] window and hands out one value per
 * window, so the main thread gets ~20 posts a second instead of one per AudioRecord read.
 *
 * The room's own noise reads as 0, so the waves lie flat between words: the meter keeps a running
 * estimate of the background level (it follows quieter windows quickly and louder ones slowly, so
 * it settles on the hum and not on the voice) and only sound [GATE_DB] above it counts.
 *
 * Reader-thread only: not thread-safe, and holds no reference to the audio it measured.
 */
internal class AudioLevelMeter(private val intervalMs: Long = 50L) {
    private var lastEmitMs: Long? = null
    private var peakDb = SILENCE_DB
    private var noiseDb = START_NOISE_DB

    /** Measures [len] bytes of [buf]; returns the window's level when one is due, else null. */
    fun offer(buf: ByteArray, len: Int, nowMs: Long): Float? {
        peakDb = maxOf(peakDb, decibels(buf, len))
        val last = lastEmitMs
        if (last != null && nowMs - last < intervalMs) return null
        lastEmitMs = nowMs
        val db = peakDb.also { peakDb = SILENCE_DB }
        val follow = if (db < noiseDb) NOISE_FALL else NOISE_RISE
        noiseDb = (noiseDb + (db - noiseDb) * follow).coerceIn(FLOOR_DB, MAX_NOISE_DB)
        return normalize(db, noiseDb + GATE_DB)
    }

    internal fun noiseDbForTest(): Double = noiseDb

    companion object {
        /** Digital silence, and the level a window starts from. */
        private const val SILENCE_DB = -120.0
        /** Nothing quieter than this ever shows, whatever the noise estimate says. */
        private const val FLOOR_DB = -55.0
        /** Normal speech close to the phone reaches roughly this; louder clamps to 1. */
        private const val CEILING_DB = -15.0
        /** A typical quiet room, where the estimate starts before it has heard anything. */
        private const val START_NOISE_DB = -50.0
        /** The estimate never climbs past this, so steady loud speech can't gate itself out. */
        private const val MAX_NOISE_DB = -32.0
        /** How far above the background a sound must be to move the waves. */
        const val GATE_DB = 6.0
        /** Share of the gap the estimate closes per window: down within a pause, up over seconds. */
        private const val NOISE_FALL = 0.5
        private const val NOISE_RISE = 0.02

        /** RMS of little-endian 16-bit mono PCM in dBFS; [SILENCE_DB] for digital silence. */
        fun decibels(buf: ByteArray, len: Int): Double {
            val samples = minOf(len, buf.size) / 2
            if (samples == 0) return SILENCE_DB
            var sum = 0.0
            for (i in 0 until samples) {
                val sample = (buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            val rms = sqrt(sum / samples)
            if (rms < 1.0) return SILENCE_DB
            return 20 * log10(rms / 32768.0)
        }

        private fun normalize(db: Double, gateDb: Double): Float {
            val floor = maxOf(gateDb, FLOOR_DB)
            if (db <= floor) return 0f
            return ((db - floor) / (CEILING_DB - floor)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
