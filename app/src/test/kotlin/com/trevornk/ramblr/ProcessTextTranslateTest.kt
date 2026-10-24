package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #360: the translate rows of the select-text picker and the routing of a translate pick. */
class ProcessTextTranslateTest {

    private val polish = CleanupPersona("polish", "Polish", "", "polish prompt")
    private val casual = CleanupPersona("casual", "Casual", "", "casual prompt")
    private val spanish = KeyboardTranslation.byCode("es")!!
    private val german = KeyboardTranslation.byCode("de")!!

    private val local = ProviderChainEntry(ProviderKind.LOCAL, "on-device")
    private val openai = ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini", id = "o1")
    private val gemini = ProviderChainEntry(ProviderKind.GEMINI, "gemini-flash", id = "g1")

    // --- Picker list ---

    @Test fun `translate rows follow every style, one per configured language, in order`() {
        val choices = ProcessTextPicker.choices(listOf(polish, casual), listOf(spanish, german))

        assertEquals(
            listOf("Polish", "Casual", "Translate → Español", "Translate → Deutsch"),
            choices.map(ProcessTextPicker::label),
        )
        assertEquals(
            listOf(
                ProcessTextChoice.Style(polish), ProcessTextChoice.Style(casual),
                ProcessTextChoice.Translate(spanish), ProcessTextChoice.Translate(german),
            ),
            choices,
        )
    }

    @Test fun `no configured languages means no translate rows`() {
        val choices = ProcessTextPicker.choices(listOf(polish, casual), emptyList())

        assertEquals(listOf("Polish", "Casual"), choices.map(ProcessTextPicker::label))
        assertTrue(choices.none { it is ProcessTextChoice.Translate })
    }

    @Test fun `the current style is preselected and a translate row never is`() {
        val choices = ProcessTextPicker.choices(listOf(polish, casual), listOf(spanish))

        assertEquals(1, ProcessTextPicker.checkedIndex(choices, "casual"))
        assertEquals(0, ProcessTextPicker.checkedIndex(choices, "no-such-style"))
        // A style key can never collide with a translate row, whatever its code is.
        assertEquals(0, ProcessTextPicker.checkedIndex(choices, "es"))
    }

    // --- Routing for a translate pick ---

    private fun plan(
        chain: List<ProviderChainEntry>,
        consentMissing: Boolean = false,
        withKey: Set<String> = setOf("o1", "g1"),
        vocabulary: List<String> = emptyList(),
        language: TranslationLanguage = spanish,
    ) = processTextTranslationPlan(language, ProviderChain(chain), consentMissing, { it.id in withKey }, vocabulary)

    @Test fun `a translate pick runs on cloud steps only and skips a leading local step`() {
        val ready = plan(listOf(local, openai, gemini)) as ProcessTextTranslationPlan.Ready

        assertEquals(listOf(openai, gemini), ready.chain.entries)
        assertTrue(ready.waterfall.steps.none { it.group == CleanupStepGroup.LOCAL_LLM })
    }

    @Test fun `the prompt is the translate-only prompt for the picked language, not a style`() {
        val ready = plan(listOf(openai)) as ProcessTextTranslationPlan.Ready

        assertEquals(
            PostProcessor.withTranslation(PostProcessor.interpolateVocabulary(PostProcessor.TRANSLATE_ONLY_PROMPT, emptyList()), "Spanish"),
            ready.prompt,
        )
        assertTrue(ready.prompt.contains("Return only the Spanish text."))
    }

    @Test fun `vocabulary terms reach the translate prompt`() {
        val ready = plan(listOf(openai), vocabulary = listOf("Ramblr")) as ProcessTextTranslationPlan.Ready

        assertTrue(ready.prompt.contains("Ramblr"))
        assertFalse(ready.prompt.contains("{{vocabulary}}"))
    }

    @Test fun `the language comes from the pick, so each language gets its own prompt`() {
        val es = plan(listOf(openai), language = spanish) as ProcessTextTranslationPlan.Ready
        val de = plan(listOf(openai), language = german) as ProcessTextTranslationPlan.Ready

        assertTrue(es.prompt.contains("Spanish") && !es.prompt.contains("German"))
        assertTrue(de.prompt.contains("German") && !de.prompt.contains("Spanish"))
    }

    @Test fun `the fidelity guard is off for a translation`() {
        val ready = plan(listOf(openai)) as ProcessTextTranslationPlan.Ready

        assertFalse(ready.fidelityGuard)
    }

    @Test fun `a missing off-device consent is reported so the activity can ask`() {
        assertEquals(
            ProcessTextTranslationPlan.Unavailable(KeyboardTranslationRouting.Reason.CONSENT_MISSING),
            plan(listOf(openai), consentMissing = true),
        )
    }

    @Test fun `no usable cloud provider is unavailable`() {
        val noReason = ProcessTextTranslationPlan.Unavailable(KeyboardTranslationRouting.Reason.NO_CLOUD_PROVIDER)

        assertEquals(noReason, plan(listOf(local)))
        assertEquals(noReason, plan(listOf(local, openai, gemini), withKey = emptySet()))
        assertEquals(noReason, plan(emptyList()))
    }
}
