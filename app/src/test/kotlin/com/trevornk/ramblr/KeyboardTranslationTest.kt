package com.trevornk.ramblr

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardTranslationTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `chosen languages keep catalog order and drop unknown codes`() {
        KeyboardTranslation.setChosen(app, listOf("ru", "xx", "en"))
        assertEquals(listOf("en", "ru"), KeyboardTranslation.chosen(app).map { it.code })
        assertEquals(listOf("de"), KeyboardTranslation.parseChosen(" de ,zz").map { it.code })
    }

    @Test fun `target must be one of the chosen languages`() {
        KeyboardTranslation.setChosen(app, listOf("en", "de"))
        KeyboardTranslation.setTarget(app, "de")
        assertEquals("German", KeyboardTranslation.target(app)?.englishName)

        KeyboardTranslation.setChosen(app, listOf("en"))
        assertNull("a language removed in Settings stops being the target", KeyboardTranslation.target(app))
    }

    @Test fun `translation prompt replaces the keep-language rule instead of contradicting it`() {
        val prompt = PostProcessor.withTranslation(PostProcessor.NATURAL_PROMPT, "German")
        assertFalse(prompt.contains("never translate."))
        assertFalse(prompt.contains(PostProcessor.KEEP_LANGUAGE_CLAUSE))
        assertTrue(prompt.trimEnd().endsWith("Return only the German text."))
        // Still recognised as Natural, which is why the runtime turns the fidelity guard off itself.
        assertTrue(PostProcessor.preservesWording(prompt))
    }

    @Test fun `translate-only prompt is not mistaken for a wording-preserving cleanup`() {
        assertFalse(PostProcessor.preservesWording(PostProcessor.withTranslation(PostProcessor.TRANSLATE_ONLY_PROMPT, "English")))
    }

    @Test fun `processProviderChain passes fidelityGuard through, so a translation is not rejected`() {
        val chain = ProviderChain(listOf(ProviderChainEntry(ProviderKind.OPENAI, "gpt-4o-mini")))
        val transport = object : CleanupHttpTransport {
            override fun send(
                url: String, headers: Map<String, String>, jsonBody: String, timeouts: CleanupStepTimeouts,
                cancelHolder: InFlightCall, callback: (CleanupHttpOutcome) -> Unit,
            ) = callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"Sag Sarah, dass die Lieferung spät ist."}}]}"""))
        }
        fun run(guard: Boolean): PostProcessor.Result {
            var result: PostProcessor.Result? = null
            PostProcessor.processProviderChain(
                text = "tell Sarah the delivery is late",
                prompt = PostProcessor.withTranslation(PostProcessor.NATURAL_PROMPT, "German"),
                chain = chain, cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
                credentialLookup = { "key" }, transport = transport, fidelityGuard = guard,
                callback = { result = it },
            )
            return result!!
        }
        assertEquals("Sag Sarah, dass die Lieferung spät ist.", run(guard = false).text)
    }
}
