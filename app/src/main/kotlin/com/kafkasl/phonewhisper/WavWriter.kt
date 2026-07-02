package com.kafkasl.phonewhisper

/** Wraps raw PCM bytes in a WAV container. */
object WavWriter {
    fun encode(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        fun putStr(off: Int, s: String) = s.forEachIndexed { i, c -> header[off + i] = c.code.toByte() }
        fun putInt(off: Int, v: Int) { for (i in 0..3) header[off + i] = (v shr (8 * i) and 0xFF).toByte() }
        fun putShort(off: Int, v: Int) { header[off] = (v and 0xFF).toByte(); header[off + 1] = (v shr 8 and 0xFF).toByte() }

        putStr(0, "RIFF")
        putInt(4, 36 + pcm.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)            // fmt chunk size
        putShort(20, 1)           // PCM format
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, blockAlign)
        putShort(34, bitsPerSample)
        putStr(36, "data")
        putInt(40, pcm.size)

        return header + pcm
    }
}
