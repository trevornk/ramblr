package com.trevornk.ramblr

import android.content.Context
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Context/network side of the remote-updatable model catalog (#98): caches a
 * successfully fetched catalog in plain SharedPreferences (the data is public -- model ids,
 * descriptions, list prices, no secrets), and resolves the actual catalog to use via
 * [ModelCatalogResolver]'s pure bundled -> cached -> fresh-fetch precedence.
 *
 * This is what lets Trevor add/retire a model by editing the published JSON at [CATALOG_URL]
 * without ever shipping a new APK -- the common-case "a provider shipped a new cheap-tier
 * model" update is a one-line JSON edit, not a rebuild.
 */
object ModelCatalogStore {
    /** Public gist, no auth required -- see trevornk/ramblr#98. Ramblr's repo is private, so a
     *  `raw.githubusercontent.com` link to a repo file would need an embedded token (itself a
     *  secret shipped in the APK, exactly what this is trying to avoid); a public gist sidesteps
     *  that entirely since the catalog data is public regardless (model ids/pricing are not
     *  secrets). */
    const val CATALOG_URL = "https://gist.githubusercontent.com/trevornk/3650fa7cc0fdecdd8c8a6cca34d33079/raw/ramblr_model_catalog.json"

    /** Re-fetch at most this often -- refreshing on every Settings screen open would be wasteful
     *  and the catalog changes rarely (only when Trevor notices a provider shipped/retired a
     *  model). [ModelCatalogResolver.isCacheStale] is the pure, independently-tested decision
     *  logic this wraps. */
    val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(1)

    private const val PREFS_NAME = "ramblr_model_catalog_cache"
    private const val KEY_CACHED_JSON = "cached_json"
    private const val KEY_CACHED_AT = "cached_at"

    /** Hard cap on the fetched catalog body (L6): the real catalog is a few KB of JSON, so anything
     *  approaching this is either a wrong URL or a hostile response and must not be buffered whole. */
    private const val MAX_CATALOG_BYTES = 512L * 1024

    /** After a failed background refresh the cache timestamp isn't advanced, so [load]/[currentCatalog]
     *  stay "stale" and would otherwise respawn a fetch on literally every call; only re-attempt once
     *  this backoff has elapsed since the last attempt (L6). */
    private val FETCH_RETRY_BACKOFF_MS = TimeUnit.MINUTES.toMillis(5)

    /** True while a background refresh thread is running, so [currentCatalog] launches at most one
     *  at a time instead of piling up an unbounded number all racing to write the same cache (L6). */
    private val fetchInFlight = AtomicBoolean(false)

    @Volatile private var lastFetchAttemptAtMs = 0L

    /** Pure decision for whether [currentCatalog] should kick off a background refresh: only when the
     *  cache is stale, no refresh is already running, and we're past the post-attempt backoff window. */
    fun shouldStartBackgroundFetch(
        isStale: Boolean,
        fetchInFlight: Boolean,
        nowMs: Long,
        lastAttemptAtMs: Long,
        backoffMs: Long,
    ): Boolean = isStale && !fetchInFlight && (nowMs - lastAttemptAtMs >= backoffMs)

    /** Short timeout, separate from [NetworkClients.shared]'s multi-minute dictation timeouts --
     *  a catalog fetch is a small JSON GET that should fail fast rather than block a Settings
     *  screen for up to 3 minutes if the gist is unreachable. */
    private val client by lazy {
        NetworkClients.shared.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The last successfully cached catalog, or null if never cached / cache is corrupted. */
    fun loadCached(context: Context): List<ModelCatalogEntry>? =
        ModelCatalogJson.deserialize(prefs(context).getString(KEY_CACHED_JSON, null))

    private fun cachedFetchedAtMs(context: Context): Long? =
        prefs(context).getLong(KEY_CACHED_AT, 0L).takeIf { it > 0L }

    private fun saveCache(context: Context, entries: List<ModelCatalogEntry>) {
        prefs(context).edit()
            .putString(KEY_CACHED_JSON, ModelCatalogJson.serialize(entries))
            .putLong(KEY_CACHED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Returns the best available catalog synchronously: a fresh network fetch if the cache is
     * stale (or empty) and reachable, otherwise the cached copy, otherwise
     * [BUNDLED_DEFAULT_MODEL_CATALOG]. Callers on Android must invoke this off the main thread
     * (it makes a blocking HTTP call when the cache is stale) -- see
     * [WhisperAccessibilityService]'s existing background-thread conventions for cleanup/
     * transcription calls.
     */
    fun load(context: Context): List<ModelCatalogEntry> {
        val cached = loadCached(context)
        val fresh = if (ModelCatalogResolver.isCacheStale(cachedFetchedAtMs(context), System.currentTimeMillis(), CACHE_TTL_MS)) {
            fetchAndCacheSync(context)
        } else {
            null
        }
        return ModelCatalogResolver.resolve(BUNDLED_DEFAULT_MODEL_CATALOG, cached, fresh)
    }

    /**
     * Non-blocking variant for UI call sites like [CloudProviderActivity]'s add/edit dialog:
     * returns whatever's immediately available (cached copy, or [BUNDLED_DEFAULT_MODEL_CATALOG]
     * if never cached) without making a network call on the calling thread, then kicks off a
     * background refresh if the cache is stale so the *next* dialog open benefits from fresher
     * data. A Settings dialog opening should never stall on a catalog fetch -- the picker is
     * useful immediately even with slightly-stale tier/description text.
     */
    fun currentCatalog(context: Context): List<ModelCatalogEntry> {
        val cached = loadCached(context)
        val immediate = ModelCatalogResolver.resolve(BUNDLED_DEFAULT_MODEL_CATALOG, cached, fresh = null)
        val now = System.currentTimeMillis()
        val stale = ModelCatalogResolver.isCacheStale(cachedFetchedAtMs(context), now, CACHE_TTL_MS)
        if (shouldStartBackgroundFetch(stale, fetchInFlight.get(), now, lastFetchAttemptAtMs, FETCH_RETRY_BACKOFF_MS) &&
            fetchInFlight.compareAndSet(false, true)
        ) {
            lastFetchAttemptAtMs = now
            val appContext = context.applicationContext
            Thread {
                try { fetchAndCacheSync(appContext) } finally { fetchInFlight.set(false) }
            }.start()
        }
        return immediate
    }

    /** Blocking fetch + cache-on-success, extracted so [load] and any future explicit
     *  "refresh now" Settings action can share the exact same network/parse/cache path. */
    private fun fetchAndCacheSync(context: Context): List<ModelCatalogEntry>? {
        val fetched = fetchFresh() ?: return null
        saveCache(context, fetched)
        return fetched
    }

    private fun fetchFresh(): List<ModelCatalogEntry>? = try {
        val request = Request.Builder().url(CATALOG_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val declaredLength = response.body?.contentLength() ?: -1L
            if (declaredLength > MAX_CATALOG_BYTES) return null
            // peekBody caps how much is read into memory even when the length isn't declared
            // (chunked); an over-cap body simply fails to parse below and degrades to the cache (L6).
            val body = response.peekBody(MAX_CATALOG_BYTES).string()
            ModelCatalogJson.deserialize(body)
        }
    } catch (e: IOException) {
        null
    } catch (e: Exception) {
        // A malformed URL or other unexpected failure should degrade to the cache chain, not
        // crash Settings -- mirrors PostProcessor/GeminiTranscriberClient's identical handling
        // of unexpected request-building failures.
        null
    }
}
