package com.trevornk.ramblr

import org.json.JSONException
import org.json.JSONObject

/**
 * Pure, Android/network-free data model + decision logic for the GitHub self-update check
 * (self-update mechanism, github distribution flavor only -- see app/build.gradle.kts's
 * "distribution" flavor split and the AGENTS note in [SelfUpdateChecker]). Split from
 * [SelfUpdateChecker] (the Context/network-owning layer) the same way [ModelCatalogResolver] is
 * split from [ModelCatalogStore] -- so JSON parsing, the versionCode-in-release-body convention,
 * and the update-available decision are each independently unit-testable without touching
 * Android or the network.
 */

/** One asset attached to a GitHub release, as returned by the Releases API. [sha256] is the hex
 *  digest with the `sha256:` prefix stripped (GitHub's `digest` field on an asset already comes
 *  back as `"sha256:<hex>"` -- see [SelfUpdateJson.parseLatestRelease] -- so this is already
 *  solved by GitHub itself; no sidecar checksum file or new release-process convention is
 *  needed for asset integrity, only for versionCode, see [parseVersionCodeFromReleaseBody]). */
data class GithubReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** The subset of a GitHub "latest release" API response this feature cares about. */
data class GithubRelease(
    val tagName: String,
    /** The release's web page (github.com/.../releases/tag/...) -- carried forward for Part 3's
     *  notify-tap-opens-browser use case, not consumed by this part. */
    val htmlUrl: String,
    val body: String,
    val assets: List<GithubReleaseAsset>,
)

/** Typed result of a self-update check, so Part 3 (Settings UI) / Part 4 (download+verify) can
 *  consume a clean result rather than raw JSON or nullable fields. */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val sha256: String?,
        val releaseUrl: String,
        val sizeBytes: Long,
    ) : UpdateCheckResult()

    object UpToDate : UpdateCheckResult()

    /** [reason] is a short, non-localized, developer-facing diagnostic (log/debug use), not
     *  user-facing copy -- Part 3 should show its own generic "couldn't check for updates"
     *  string rather than surfacing this text directly. */
    data class CheckFailed(val reason: String) : UpdateCheckResult()
}

object SelfUpdateJson {
    /**
     * Parses a raw `GET /repos/trevornk/ramblr/releases/latest` JSON response body into a
     * [GithubRelease], or null if the body is blank/malformed or missing a required field
     * (tag_name, html_url, body, or the assets array itself -- individual asset fields are
     * best-effort: a missing digest/size on one asset doesn't fail the whole parse, since only
     * the one matching APK asset is used downstream and [GithubReleaseAsset.sha256] is already
     * nullable).
     */
    fun parseLatestRelease(raw: String?): GithubRelease? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val assetsArray = obj.getJSONArray("assets")
            val assets = (0 until assetsArray.length()).map { i ->
                val assetObj = assetsArray.getJSONObject(i)
                GithubReleaseAsset(
                    name = assetObj.getString("name"),
                    browserDownloadUrl = assetObj.getString("browser_download_url"),
                    sizeBytes = assetObj.optLong("size", -1L),
                    sha256 = assetObj.optString("digest", "").removePrefix("sha256:").takeIf { it.isNotBlank() },
                )
            }
            GithubRelease(
                tagName = obj.getString("tag_name"),
                htmlUrl = obj.getString("html_url"),
                body = obj.optString("body", ""),
                assets = assets,
            )
        } catch (e: JSONException) {
            null
        }
    }
}

object SelfUpdateResolver {
    /** Matches a clean `vX.Y.Z` release tag (e.g. `v1.0.10`) and rejects everything else --
     *  in particular the noisy `v1.0.9-dev.202607092355.b6e721f`-style tags release.yml
     *  publishes on every push to main. `/releases/latest` already excludes prereleases/drafts
     *  at the API level, but this is an explicit extra safety net in case that endpoint's
     *  semantics ever change or a dev tag is accidentally marked as a real release. */
    private val CLEAN_RELEASE_TAG = Regex("""^v\d+\.\d+\.\d+$""")

    fun isCleanReleaseTag(tagName: String): Boolean = CLEAN_RELEASE_TAG.matches(tagName)

    /** Matches the github-flavor release APK's AGP-generated output filename -- see
     *  app/build.gradle.kts's `applicationVariants.all { outputs.all { ... } }` block:
     *  `"Ramblr-${versionName}-${flavorName}-${buildType.name}.apk"`, i.e.
     *  `Ramblr-1.0.10-github-release.apk` for a github-flavor release build. Releases published
     *  before the distribution flavor split (0f8cab2) used the older flavor-less
     *  `Ramblr-<version>-release.apk` naming (e.g. the real v1.0.10 asset) and simply won't
     *  match here -- expected, since those predate the versionCode-in-body convention below too
     *  and can't be sensibly offered as a self-update target regardless. */
    private val GITHUB_RELEASE_APK_NAME = Regex("""^Ramblr-\d+\.\d+\.\d+-github-release\.apk$""")

    fun findReleaseApkAsset(assets: List<GithubReleaseAsset>): GithubReleaseAsset? =
        assets.firstOrNull { GITHUB_RELEASE_APK_NAME.matches(it.name) }

    /**
     * Extracts an embedded versionCode from a GitHub release's body text.
     *
     * ## The versionCode-in-release-body convention
     *
     * GitHub releases don't carry an Android versionCode natively (only the tag/name, which
     * this repo uses for versionName). To let the update checker compare versionCodes without
     * a second network call, every real (clean `vX.Y.Z`) release's body **must** contain a line
     * matching, case-insensitively:
     *
     * ```
     * versionCode: <int>
     * ```
     *
     * e.g. `versionCode: 13`. The match is deliberately lenient on whitespace/colon/casing
     * (`version code 13`, `VersionCode:13`, `Version code: 13` all parse) so it survives being
     * folded into a prose sentence -- e.g. the real v1.0.10 release body already happens to
     * contain "Signed release build, versionCode 13." and parses correctly as-is, without
     * needing to be rewritten. New releases should still prefer a dedicated `versionCode: 13`
     * line for clarity, but this function does not require that exact form.
     *
     * Returns null if no such line is present, or if the captured value isn't a valid Int
     * (e.g. `versionCode: thirteen`) -- both cases degrade to [UpdateCheckResult.CheckFailed]
     * upstream rather than crashing or guessing.
     */
    private val VERSION_CODE_PATTERN = Regex("""(?i)version\s*code[:\s]+(-?\d+)""")

    fun parseVersionCodeFromReleaseBody(body: String): Int? =
        VERSION_CODE_PATTERN.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The single pure decision this whole feature exists to make: given the latest [release]
     * (null if the fetch itself failed) and the running app's own [currentVersionCode]
     * ([BuildConfig.VERSION_CODE]), decide whether a self-update is available.
     */
    fun evaluate(release: GithubRelease?, currentVersionCode: Int): UpdateCheckResult {
        if (release == null) return UpdateCheckResult.CheckFailed("fetch failed or returned unparseable JSON")
        if (!isCleanReleaseTag(release.tagName)) {
            return UpdateCheckResult.CheckFailed("latest release tag '${release.tagName}' is not a clean vX.Y.Z release")
        }
        val remoteVersionCode = parseVersionCodeFromReleaseBody(release.body)
            ?: return UpdateCheckResult.CheckFailed("release body has no parseable versionCode line")
        val asset = findReleaseApkAsset(release.assets)
            ?: return UpdateCheckResult.CheckFailed("no github-flavor release APK asset found")

        return if (remoteVersionCode > currentVersionCode) {
            UpdateCheckResult.UpdateAvailable(
                versionName = release.tagName.removePrefix("v"),
                versionCode = remoteVersionCode,
                downloadUrl = asset.browserDownloadUrl,
                sha256 = asset.sha256,
                releaseUrl = release.htmlUrl,
                sizeBytes = asset.sizeBytes,
            )
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** True when [lastCheckedAtMs] is older than [ttlMs] (or never checked -- null/0), mirrors
     *  [ModelCatalogResolver.isCacheStale] exactly. */
    fun isCacheStale(lastCheckedAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean =
        lastCheckedAtMs == null || nowMs - lastCheckedAtMs >= ttlMs
}
