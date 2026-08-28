package com.trevornk.ramblr

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

    /**
     * [shared], but refusing to follow redirects -- for calls that carry a credential header or an
     * audio body to a host validated up front.
     *
     * OkHttp follows redirects by default and only strips `Authorization` on a host change; a
     * custom credential header like `x-goog-api-key` is carried across hosts untouched. Validating
     * a URL's origin before the call is therefore not sufficient on its own: a 3xx from the
     * validated host can still redirect the request, its credential header, and its body to an
     * arbitrary third-party host. Callers using this client see the 3xx as a plain unsuccessful
     * response and fail the operation instead.
     */
    val noRedirects: OkHttpClient by lazy {
        shared.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
