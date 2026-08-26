package com.trevornk.ramblr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Membership spot-checks against the real bundled suggestion-filter list (#216),
 * `res/raw/suggestion_filter_words.txt`, loaded through the same parser production uses
 * ([SuggestionFilterDictionary.load]). Locates the repo file by walking up from the test
 * working directory, the [ModelCatalogFileSyncTest] pattern.
 *
 * The invariant these tests pin down: common English IS in the list (so ordinary words are
 * never suggested), and names are NOT (so the whole feature can work at all). If a
 * regeneration of the list ever breaks either direction, this fails the build.
 */
class SuggestionFilterDictionaryTest {

    private val words: Set<String> by lazy {
        SuggestionFilterDictionary.load(locateListFile().inputStream())
    }

    @Test fun `list loads and is in the expected size band`() {
        assertTrue("expected tens of thousands of words, got ${words.size}", words.size in 20_000..80_000)
    }

    @Test fun `common english words are present`() {
        for (word in listOf(
            "the", "house", "color", "colour", "practice", "practise", "honourable",
            "sheriff", "governor", "constitution", "soliloquy",
        )) {
            assertTrue("expected common word '$word' in filter list", word in words)
        }
    }

    @Test fun `names are absent so they can be suggested`() {
        for (name in listOf("hetzner", "elsinore", "margolotte", "bracton", "solveit")) {
            assertFalse("name '$name' must NOT be in the filter list", name in words)
        }
    }

    @Test fun `entries are lowercase single words`() {
        assertTrue(words.all { w -> w.all { it in 'a'..'z' } })
    }

    @Test fun `blank lines are ignored by the parser`() {
        val parsed = SuggestionFilterDictionary.load("alpha\n\n  \nbeta\n".byteInputStream())
        assertTrue(parsed == setOf("alpha", "beta"))
    }

    private fun locateListFile(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?.let { File(it, "app/src/main/res/raw/suggestion_filter_words.txt") }
            ?.takeIf { it.isFile }
            ?: error("could not locate suggestion_filter_words.txt from ${File("").absolutePath}")
}
