package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client for transcription + cleanup calls. OkHttp's defaults (10s connect/read/
 * write, no call timeout) kill multi-minute recordings after the audio has already been
 * uploaded. Timeouts here are sized for that worst case instead. See #14.
 */
object NetworkClients {
    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 120L
    const val WRITE_TIMEOUT_SECONDS = 120L
    const val CALL_TIMEOUT_SECONDS = 180L

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
