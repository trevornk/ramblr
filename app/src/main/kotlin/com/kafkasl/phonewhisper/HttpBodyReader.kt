package com.kafkasl.phonewhisper

import okhttp3.Response
import java.io.IOException

/**
 * Reads a [Response] body defensively (#62). `ResponseBody.string()` performs real socket reads
 * and throws [IOException] when the connection stalls or resets mid-body — and an exception
 * thrown from inside `Callback.onResponse` is swallowed by OkHttp (logged as "Callback failure",
 * `onFailure` is never invoked since the callback was already signalled), so the caller's
 * completion callback would never fire at all: the cleanup waterfall would neither succeed nor
 * advance, leaving the dictation hanging until the 400s watchdog discards it. Every
 * `onResponse` in this app must read the body through here and route a failure into the same
 * path a connection failure takes.
 */
object HttpBodyReader {
    /** The body as a string (empty if absent), or a failed [Result] carrying the [IOException]
     *  if the read died mid-stream. Never throws. */
    fun read(response: Response): Result<String> = try {
        Result.success(response.body?.string() ?: "")
    } catch (e: IOException) {
        Result.failure(e)
    }
}
