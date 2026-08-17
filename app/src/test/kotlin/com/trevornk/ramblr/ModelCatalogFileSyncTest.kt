package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Pins the repo-root `model-catalog.json` to [BUNDLED_DEFAULT_MODEL_CATALOG].
 *
 * That file is the human-editable source for the published gist at
 * [ModelCatalogStore.CATALOG_URL] -- the app never reads it directly, which is exactly why it
 * silently rotted: it still advertised `gemini-2.5-flash-lite` and `gemini-2.5-flash` (both on
 * Google's shutdown path) long after the bundled catalog had migrated to `gemini-3.1-flash-lite`
 * and `gemini-3.5-flash`, and nothing failed. An unreferenced duplicate that nobody validates is
 * worse than no file at all, because the next person to publish the gist would have pushed
 * deprecated model IDs to every install.
 *
 * Regenerate after changing the bundled catalog:
 *
 * ```
 * ./gradlew testGithubDebugUnitTest --offline --tests '*ModelCatalogFileSyncTest*' \
 *     -Dramblr.writeModelCatalog=true
 * ```
 *
 * then publish the regenerated file's contents to the gist. Keeping regeneration in the test
 * rather than a Gradle task means the check and the fix can never disagree about the format.
 */
class ModelCatalogFileSyncTest {

    companion object {
        /** Runs once, before any test reads the file. Regeneration must not live inside a test
         *  method: the other cases read the same file, so a rewrite mid-class would make results
         *  depend on JUnit's method order -- which is exactly the kind of order-dependent test
         *  that passes locally and fails in CI for no visible reason. */
        @JvmStatic
        @BeforeClass
        fun regenerateIfRequested() {
            if (System.getProperty("ramblr.writeModelCatalog") != "true") return
            val file = locateCatalogFile()
            file.writeText(renderPretty())
            println("regenerated ${file.absolutePath}")
        }

        private fun locateCatalogFile(): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "model-catalog.json").isFile && File(it, "settings.gradle.kts").isFile }
                ?.let { File(it, "model-catalog.json") }
                ?: error("could not locate model-catalog.json from ${File("").absolutePath}")

        /** Pretty-prints so the tracked file stays reviewable in a diff; the gist doesn't care
         *  about whitespace and [ModelCatalogJson.deserialize] is whitespace-insensitive. */
        private fun renderPretty(): String =
            org.json.JSONArray(ModelCatalogJson.serialize(BUNDLED_DEFAULT_MODEL_CATALOG)).toString(2) + "\n"
    }

    private val catalogFile: File by lazy { locateCatalogFile() }

    @Test
    fun `root model-catalog json matches the bundled default catalog`() {
        val parsed = ModelCatalogJson.deserialize(catalogFile.readText())
        assertEquals(
            "model-catalog.json is out of sync with BUNDLED_DEFAULT_MODEL_CATALOG; " +
                "regenerate with -Dramblr.writeModelCatalog=true and republish the gist",
            BUNDLED_DEFAULT_MODEL_CATALOG,
            parsed,
        )
    }

    @Test
    fun `published catalog carries no model id that the bundled catalog has retired`() {
        val parsed = ModelCatalogJson.deserialize(catalogFile.readText())
            ?: error("model-catalog.json failed to parse")
        val bundledIds = BUNDLED_DEFAULT_MODEL_CATALOG.map { it.provider to it.modelId }.toSet()
        val strayIds = parsed.map { it.provider to it.modelId }.filterNot { it in bundledIds }
        assertEquals("model-catalog.json advertises models the app no longer ships", emptyList<Any>(), strayIds)
    }

    @Test
    fun `every transcription-capable Gemini entry is one the transcriber can actually be pointed at`() {
        // GeminiTranscriberClient.DEFAULT_MODEL has to be a model the catalog still offers,
        // otherwise the shipped default is unreachable from the picker.
        val geminiIds = BUNDLED_DEFAULT_MODEL_CATALOG
            .filter { it.provider == ProviderKind.GEMINI && it.useCase != ModelUseCase.CLEANUP }
            .map { it.modelId }
        assertTrue(
            "GeminiTranscriberClient.DEFAULT_MODEL (${GeminiTranscriberClient.DEFAULT_MODEL}) " +
                "is not a transcription-capable catalog entry; catalog offers $geminiIds",
            GeminiTranscriberClient.DEFAULT_MODEL in geminiIds,
        )
    }
}
