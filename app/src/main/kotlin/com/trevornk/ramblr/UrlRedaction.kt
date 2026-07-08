package com.trevornk.ramblr

/**
 * Strips query strings from any URL that appears inside an error/log message before it reaches
 * logcat or a user-visible toast. Defense in depth for the credential-in-URL leak class (M-3 /
 * security audit L-3): API keys now travel in request headers, not `?key=` query parameters, so
 * the URL itself no longer carries a secret -- but an echoed OkHttp exception message
 * ("...url=https://host/path?key=SECRET...") from any future endpoint that still uses a query
 * string must never leak the `?...` portion into a log line. Replaces everything from the first
 * `?` of each `http(s)://...` token with `?<redacted>`.
 */
object UrlRedaction {
    private val URL_WITH_QUERY = Regex("""(https?://\S*?)\?\S*""")

    fun redact(message: String?): String? =
        message?.replace(URL_WITH_QUERY) { "${it.groupValues[1]}?<redacted>" }
}
