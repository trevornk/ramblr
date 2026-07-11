package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure JSON parsing / versionCode-in-body convention / update decision / cache
 *  staleness logic of [SelfUpdateJson] and [SelfUpdateResolver] (self-update mechanism, github
 *  distribution flavor only). Lives in src/testGithub/ (not src/test/) because the production
 *  code under test lives in src/github/ -- mirrors the app/build.gradle.kts flavor split;
 *  storefront's test task never sees this file or the code it exercises. */
class SelfUpdateResolverTest {

    // Representative fixture modeled directly on the real
    // `gh release view v1.0.10 --repo trevornk/ramblr --json body,assets` output (see
    // SelfUpdateChecker.kt's KDoc), with a clean vX.Y.Z tag, a github-flavor release APK asset
    // name, and a versionCode line folded into prose (the real v1.0.10 body says "versionCode
    // 13" mid-sentence, exactly what parseVersionCodeFromReleaseBody must still parse).
    private val sampleReleaseJson = """
        {
          "tag_name": "v1.0.11",
          "html_url": "https://github.com/trevornk/ramblr/releases/tag/v1.0.11",
          "body": "Signed release build, versionCode: 14. Some release notes here.",
          "assets": [
            {
              "name": "Ramblr-1.0.11-github-release.apk",
              "browser_download_url": "https://github.com/trevornk/ramblr/releases/download/v1.0.11/Ramblr-1.0.11-github-release.apk",
              "size": 57400000,
              "digest": "sha256:abc123def456"
            },
            {
              "name": "Ramblr-1.0.11-storefront-release.apk",
              "browser_download_url": "https://github.com/trevornk/ramblr/releases/download/v1.0.11/Ramblr-1.0.11-storefront-release.apk",
              "size": 57300000,
              "digest": "sha256:deadbeef"
            }
          ]
        }
    """.trimIndent()

    // --- SelfUpdateJson.parseLatestRelease ---

    @Test fun `parses a realistic release response`() {
        val release = SelfUpdateJson.parseLatestRelease(sampleReleaseJson)
        assertEquals("v1.0.11", release?.tagName)
        assertEquals("https://github.com/trevornk/ramblr/releases/tag/v1.0.11", release?.htmlUrl)
        assertEquals(2, release?.assets?.size)
        val githubAsset = release?.assets?.get(0)
        assertEquals("Ramblr-1.0.11-github-release.apk", githubAsset?.name)
        assertEquals("abc123def456", githubAsset?.sha256)
        assertEquals(57400000L, githubAsset?.sizeBytes)
    }

    @Test fun `parseLatestRelease returns null for blank or malformed json`() {
        assertNull(SelfUpdateJson.parseLatestRelease(null))
        assertNull(SelfUpdateJson.parseLatestRelease(""))
        assertNull(SelfUpdateJson.parseLatestRelease("not json"))
    }

    @Test fun `parseLatestRelease returns null when a required top-level field is missing`() {
        assertNull(SelfUpdateJson.parseLatestRelease("""{"tag_name": "v1.0.11"}"""))
    }

    @Test fun `parseLatestRelease tolerates an asset with no digest`() {
        val json = """
            {"tag_name": "v1.0.11", "html_url": "https://x", "body": "versionCode: 14",
             "assets": [{"name": "a.apk", "browser_download_url": "https://x/a.apk", "size": 10}]}
        """.trimIndent()
        val release = SelfUpdateJson.parseLatestRelease(json)
        assertNull(release?.assets?.get(0)?.sha256)
    }

    // --- SelfUpdateResolver.isCleanReleaseTag ---

    @Test fun `accepts clean vX Y Z tags`() {
        assertTrue(SelfUpdateResolver.isCleanReleaseTag("v1.0.10"))
        assertTrue(SelfUpdateResolver.isCleanReleaseTag("v2.15.100"))
    }

    @Test fun `rejects dev build tags`() {
        assertEquals(false, SelfUpdateResolver.isCleanReleaseTag("v1.0.9-dev.202607092355.b6e721f"))
        assertEquals(false, SelfUpdateResolver.isCleanReleaseTag("1.0.10"))
        assertEquals(false, SelfUpdateResolver.isCleanReleaseTag("v1.0"))
    }

    // --- SelfUpdateResolver.findReleaseApkAsset ---

    @Test fun `finds the github-flavor release apk asset and ignores storefront's`() {
        val release = SelfUpdateJson.parseLatestRelease(sampleReleaseJson)!!
        val asset = SelfUpdateResolver.findReleaseApkAsset(release.assets)
        assertEquals("Ramblr-1.0.11-github-release.apk", asset?.name)
    }

    @Test fun `findReleaseApkAsset returns null when no matching asset exists`() {
        assertNull(SelfUpdateResolver.findReleaseApkAsset(emptyList()))
        assertNull(
            SelfUpdateResolver.findReleaseApkAsset(
                listOf(GithubReleaseAsset("Ramblr-1.0.10-release.apk", "https://x", 100L, null))
            )
        )
    }

    // --- SelfUpdateResolver.parseVersionCodeFromReleaseBody ---

    @Test fun `parses a dedicated versionCode line`() {
        assertEquals(14, SelfUpdateResolver.parseVersionCodeFromReleaseBody("versionCode: 14"))
    }

    @Test fun `parses versionCode folded into prose, case-insensitive, various separators`() {
        assertEquals(13, SelfUpdateResolver.parseVersionCodeFromReleaseBody("Signed release build, versionCode 13. More notes."))
        assertEquals(13, SelfUpdateResolver.parseVersionCodeFromReleaseBody("VersionCode:13"))
        assertEquals(13, SelfUpdateResolver.parseVersionCodeFromReleaseBody("Version code: 13"))
    }

    @Test fun `returns null when there is no versionCode line`() {
        assertNull(SelfUpdateResolver.parseVersionCodeFromReleaseBody("Just some release notes, no metadata."))
    }

    @Test fun `returns null for a malformed non-integer value`() {
        assertNull(SelfUpdateResolver.parseVersionCodeFromReleaseBody("versionCode: thirteen"))
    }

    // --- SelfUpdateResolver.evaluate ---

    @Test fun `evaluate reports UpdateAvailable when remote versionCode is newer`() {
        val release = SelfUpdateJson.parseLatestRelease(sampleReleaseJson)
        val result = SelfUpdateResolver.evaluate(release, currentVersionCode = 13)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.0.11", update.versionName)
        assertEquals(14, update.versionCode)
        assertEquals("abc123def456", update.sha256)
        assertEquals("https://github.com/trevornk/ramblr/releases/download/v1.0.11/Ramblr-1.0.11-github-release.apk", update.downloadUrl)
        assertEquals("https://github.com/trevornk/ramblr/releases/tag/v1.0.11", update.releaseUrl)
        assertEquals(57400000L, update.sizeBytes)
    }

    @Test fun `evaluate reports UpToDate when remote versionCode equals current`() {
        val release = SelfUpdateJson.parseLatestRelease(sampleReleaseJson)
        assertEquals(UpdateCheckResult.UpToDate, SelfUpdateResolver.evaluate(release, currentVersionCode = 14))
    }

    @Test fun `evaluate reports UpToDate when remote versionCode is older`() {
        val release = SelfUpdateJson.parseLatestRelease(sampleReleaseJson)
        assertEquals(UpdateCheckResult.UpToDate, SelfUpdateResolver.evaluate(release, currentVersionCode = 20))
    }

    @Test fun `evaluate reports CheckFailed when the fetch itself failed (null release)`() {
        val result = SelfUpdateResolver.evaluate(null, currentVersionCode = 13)
        assertTrue(result is UpdateCheckResult.CheckFailed)
    }

    @Test fun `evaluate reports CheckFailed for a dev-tag release even if the API returned it`() {
        val release = GithubRelease(
            tagName = "v1.0.9-dev.202607092355.b6e721f",
            htmlUrl = "https://x",
            body = "versionCode: 99",
            assets = emptyList(),
        )
        val result = SelfUpdateResolver.evaluate(release, currentVersionCode = 13)
        assertTrue(result is UpdateCheckResult.CheckFailed)
    }

    @Test fun `evaluate reports CheckFailed when the release body has no versionCode line`() {
        val release = GithubRelease(
            tagName = "v1.0.11", htmlUrl = "https://x", body = "no metadata here",
            assets = listOf(GithubReleaseAsset("Ramblr-1.0.11-github-release.apk", "https://x/a.apk", 100L, "aaa")),
        )
        assertTrue(SelfUpdateResolver.evaluate(release, currentVersionCode = 13) is UpdateCheckResult.CheckFailed)
    }

    @Test fun `evaluate reports CheckFailed when no github release apk asset is present`() {
        val release = GithubRelease(
            tagName = "v1.0.11", htmlUrl = "https://x", body = "versionCode: 14",
            assets = listOf(GithubReleaseAsset("Ramblr-1.0.11-storefront-release.apk", "https://x/a.apk", 100L, "aaa")),
        )
        assertTrue(SelfUpdateResolver.evaluate(release, currentVersionCode = 13) is UpdateCheckResult.CheckFailed)
    }

    // --- SelfUpdateResolver.isCacheStale ---

    private val ttl = 4 * 60 * 60 * 1000L

    @Test fun `isCacheStale is true when never checked`() {
        assertTrue(SelfUpdateResolver.isCacheStale(null, nowMs = 10 * ttl, ttlMs = ttl))
    }

    @Test fun `isCacheStale is false within the ttl`() {
        assertEquals(false, SelfUpdateResolver.isCacheStale(lastCheckedAtMs = 0L, nowMs = ttl - 1, ttlMs = ttl))
    }

    @Test fun `isCacheStale is true once the ttl has elapsed`() {
        assertTrue(SelfUpdateResolver.isCacheStale(lastCheckedAtMs = 0L, nowMs = ttl, ttlMs = ttl))
    }
}
