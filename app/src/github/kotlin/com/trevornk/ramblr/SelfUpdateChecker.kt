package com.trevornk.ramblr

import android.content.Context
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Owns the Context/network side of the GitHub self-update check (github distribution flavor
 * only -- see app/build.gradle.kts's "distribution" flavor split). Fetches
 * `GET /repos/trevornk/ramblr/releases/latest` (public repo, no auth required -- confirmed via
 * a live unauthenticated call), caches the raw response body + fetch timestamp in plain
 * SharedPreferences (public release metadata, no secrets), and hands the parse/decide work to
 * [SelfUpdateJson]/[SelfUpdateResolver] -- mirrors [ModelCatalogStore]'s split from
 * [ModelCatalogResolver] exactly.
 *
 * AGENTS note (why this lives under app/src/github/kotlin/ and not app/src/main/): Google Play's
 * Developer Program Policy bans an app updating itself by any method other than Play's own
 * mechanism, and Trevor wants to keep pursuing both Play and F-Droid listings. A boolean flag
 * (see BuildConfig.SELF_UPDATE_ENABLED) can't satisfy that -- the code must be physically absent
 * from the compiled classes of any non-github-flavor build, which is exactly what a flavor-
 * specific source set gives us: the "storefront" flavor's compile task never even sees this
 * file. Do not move this logic into app/src/main/ gated behind a runtime check.
 *
 * The cache stores the *raw JSON*, not a precomputed [UpdateCheckResult] -- because the decision
 * depends on the running app's own versionCode ([BuildConfig.VERSION_CODE]), which can itself
 * change if this very app self-updates; re-evaluating against the current versionCode on every
 * read (cheap, pure, no I/O) avoids ever serving a stale "update available" for a version the
 * app has already moved past.
 */
object SelfUpdateChecker {
    const val RELEASES_LATEST_URL = "https://api.github.com/repos/trevornk/ramblr/releases/latest"

    /** Re-check at most this often. This is polled by a periodic WorkManager job (Part 5, not
     *  this part) rather than a Settings-screen-open event, so unlike [ModelCatalogStore]'s
     *  1-day TTL (a rarely-changing catalog), this needs to be short enough that the periodic
     *  job's own cadence (expected every few hours) actually produces fresh checks rather than
     *  hitting a cache the whole time. 4 hours: frequent enough to notice a new release same-day,
     *  rare enough that hammering the GitHub API on every foreground/job trigger is unnecessary
     *  (a real release is at most a few times a week). */
    val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(4)

    private const val PREFS_NAME = "ramblr_self_update_cache"
    private const val KEY_CACHED_JSON = "cached_json"
    private const val KEY_CACHED_AT = "cached_at"

    /** Hard cap on the fetched response body -- a real release JSON (a handful of assets, a
     *  release-notes body) is a few KB; anything approaching this is a wrong URL or a hostile
     *  response and must not be buffered whole. Mirrors [ModelCatalogStore.MAX_CATALOG_BYTES]. */
    private const val MAX_RESPONSE_BYTES = 512L * 1024

    /** Short timeout, separate from [NetworkClients.shared]'s multi-minute dictation timeouts --
     *  this is a small JSON GET that should fail fast. Mirrors [ModelCatalogStore.client]. */
    private val client by lazy {
        NetworkClients.shared.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun cachedJson(context: Context): String? = prefs(context).getString(KEY_CACHED_JSON, null)

    private fun cachedFetchedAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_CACHED_AT, 0L).takeIf { it > 0L }

    private fun saveCache(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_CACHED_JSON, json)
            .putLong(KEY_CACHED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Returns the current self-update status, synchronously. Uses the cached response if it's
     * still fresh (per [CACHE_TTL_MS]); otherwise attempts a fresh fetch, caching it on success
     * and falling back to any existing (even if stale) cached copy on failure so a transient
     * network blip doesn't flip a previously-known "update available" back to "check failed".
     * Callers on Android must invoke this off the main thread (it can make a blocking HTTP call).
     */
    fun check(context: Context): UpdateCheckResult {
        val now = System.currentTimeMillis()
        val json = if (SelfUpdateResolver.isCacheStale(cachedFetchedAtMs(context), now, CACHE_TTL_MS)) {
            fetchFresh()?.also { saveCache(context, it) } ?: cachedJson(context)
        } else {
            cachedJson(context)
        }
        val release = SelfUpdateJson.parseLatestRelease(json)
        return SelfUpdateResolver.evaluate(release, com.trevornk.ramblr.BuildConfig.VERSION_CODE)
    }

    /** Re-evaluates the *existing* cached response (no network call) against the running app's
     *  current versionCode, for Settings UI (Part 3) to render a status row without forcing a
     *  fetch on every screen open -- only an explicit "Check now" (or Part 5's periodic job)
     *  should hit the network. Returns null if nothing has ever been cached yet. */
    fun cachedResult(context: Context): UpdateCheckResult? {
        val json = cachedJson(context) ?: return null
        val release = SelfUpdateJson.parseLatestRelease(json)
        return SelfUpdateResolver.evaluate(release, com.trevornk.ramblr.BuildConfig.VERSION_CODE)
    }

    /** When the cache was last actually fetched (not merely re-evaluated), for the Settings
     *  status row's "last checked" line. Null if never checked. */
    fun lastCheckedAtMs(context: Context): Long? = cachedFetchedAtMs(context)

    private fun fetchFresh(): String? = try {
        val request = Request.Builder().url(RELEASES_LATEST_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val declaredLength = response.body?.contentLength() ?: -1L
            if (declaredLength > MAX_RESPONSE_BYTES) return null
            // peekBody caps how much is read into memory even when the length isn't declared
            // (chunked); an over-cap body simply fails to parse below and degrades to the cache.
            response.peekBody(MAX_RESPONSE_BYTES).string()
        }
    } catch (e: IOException) {
        null
    } catch (e: Exception) {
        // A malformed URL or other unexpected failure should degrade to the cache, not crash --
        // mirrors ModelCatalogStore/PostProcessor's identical handling.
        null
    }
}
