package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPersonaTest {

    // --- Built-in personas resolve to the exact original prompt constants (#40) ---
    // These byte-for-byte comparisons are the regression guard for "existing users' saved preset
    // selection must keep working identically" -- any drift here changes what an existing user's
    // saved "formal"/"casual"/"notes" selection actually sends to the cleanup model.

    @Test fun `formal maps to dev prompt byte for byte`() {
        assertEquals(PostProcessor.DEV_PROMPT, CleanupPersonas.FORMAL.prompt)
    }

    @Test fun `casual maps to simple prompt byte for byte`() {
        assertEquals(PostProcessor.SIMPLE_PROMPT, CleanupPersonas.CASUAL.prompt)
    }

    @Test fun `notes maps to structured prompt byte for byte`() {
        assertEquals(PostProcessor.STRUCTURED_PROMPT, CleanupPersonas.NOTES.prompt)
    }

    @Test fun `original three keys are unchanged so existing saved selections still resolve`() {
        assertEquals("formal", CleanupPersonas.FORMAL.key)
        assertEquals("casual", CleanupPersonas.CASUAL.key)
        assertEquals("notes", CleanupPersonas.NOTES.key)
    }

    // --- New personas (#40) exist and use their own distinct prompts ---

    @Test fun `gangster maps to gangster prompt`() {
        assertEquals(PostProcessor.GANGSTER_PROMPT, CleanupPersonas.GANGSTER.prompt)
    }

    @Test fun `smart maps to smart prompt`() {
        assertEquals(PostProcessor.SMART_PROMPT, CleanupPersonas.SMART.prompt)
    }

    @Test fun `teacher maps to teacher prompt`() {
        assertEquals(PostProcessor.TEACHER_PROMPT, CleanupPersonas.TEACHER.prompt)
    }

    // --- Persona list integrity ---

    @Test fun `built-in list contains all six personas`() {
        assertEquals(6, CleanupPersonas.BUILT_IN.size)
        assertEquals(
            setOf("formal", "casual", "notes", "gangster", "smart", "teacher"),
            CleanupPersonas.BUILT_IN.map { it.key }.toSet()
        )
    }

    @Test fun `every built-in persona has a unique, non-blank key`() {
        val keys = CleanupPersonas.BUILT_IN.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }

    @Test fun `every built-in persona has non-blank title, subtitle, and prompt`() {
        for (persona in CleanupPersonas.BUILT_IN) {
            assertTrue("${persona.key} title", persona.title.isNotBlank())
            assertTrue("${persona.key} subtitle", persona.subtitle.isNotBlank())
            assertTrue("${persona.key} prompt", persona.prompt.isNotBlank())
        }
    }

    // --- Lookup / default / resolvePrompt logic (unchanged from the pre-#40 CleanupStyle) ---

    @Test fun `default persona is formal`() {
        assertSame(CleanupPersonas.FORMAL, CleanupPersonas.DEFAULT)
    }

    @Test fun `fromKey resolves every built-in key to the same instance`() {
        for (persona in CleanupPersonas.BUILT_IN) {
            assertSame(persona, CleanupPersonas.fromKey(persona.key))
        }
    }

    @Test fun `fromKey falls back to default for unknown or null keys`() {
        assertSame(CleanupPersonas.DEFAULT, CleanupPersonas.fromKey("nonsense"))
        assertSame(CleanupPersonas.DEFAULT, CleanupPersonas.fromKey(null))
    }

    @Test fun `resolvePrompt uses the persona prompt when there is no custom prompt`() {
        assertEquals(CleanupPersonas.CASUAL.prompt, CleanupPersonas.resolvePrompt(CleanupPersonas.CASUAL, null))
        assertEquals(CleanupPersonas.CASUAL.prompt, CleanupPersonas.resolvePrompt(CleanupPersonas.CASUAL, ""))
        assertEquals(CleanupPersonas.CASUAL.prompt, CleanupPersonas.resolvePrompt(CleanupPersonas.CASUAL, "   "))
    }

    @Test fun `resolvePrompt uses the persona prompt when the custom prompt is unedited`() {
        // custom_post_processing_prompt defaults to PostProcessor.DEFAULT_PROMPT until a user
        // actually edits it, so an unedited "custom" prompt must not count as custom.
        assertEquals(
            CleanupPersonas.NOTES.prompt,
            CleanupPersonas.resolvePrompt(CleanupPersonas.NOTES, PostProcessor.DEFAULT_PROMPT)
        )
    }

    @Test fun `an explicit custom prompt always wins over every persona`() {
        val custom = "Always write in pirate speak."
        for (persona in CleanupPersonas.BUILT_IN) {
            assertEquals(custom, CleanupPersonas.resolvePrompt(persona, custom))
        }
    }
}
