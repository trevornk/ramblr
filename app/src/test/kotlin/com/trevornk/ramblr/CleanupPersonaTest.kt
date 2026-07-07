package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPersonaTest {

    // --- Built-in personas resolve to the exact original prompt constants (#40, #103) ---
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

    @Test fun `email maps to email prompt byte for byte`() {
        assertEquals(PostProcessor.EMAIL_PROMPT, CleanupPersonas.EMAIL.prompt)
    }

    @Test fun `concise maps to concise prompt byte for byte`() {
        assertEquals(PostProcessor.CONCISE_PROMPT, CleanupPersonas.CONCISE.prompt)
    }

    @Test fun `original three keys are unchanged so existing saved selections still resolve`() {
        assertEquals("formal", CleanupPersonas.FORMAL.key)
        assertEquals("casual", CleanupPersonas.CASUAL.key)
        assertEquals("notes", CleanupPersonas.NOTES.key)
    }

    // --- Retired personas (#103): demoted out of BUILT_IN, still resolvable by key ---

    @Test fun `gangster maps to gangster prompt`() {
        assertEquals(PostProcessor.GANGSTER_PROMPT, CleanupPersonas.GANGSTER.prompt)
    }

    @Test fun `smart maps to smart prompt`() {
        assertEquals(PostProcessor.SMART_PROMPT, CleanupPersonas.SMART.prompt)
    }

    @Test fun `teacher maps to teacher prompt`() {
        assertEquals(PostProcessor.TEACHER_PROMPT, CleanupPersonas.TEACHER.prompt)
    }

    @Test fun `legacy retired personas are not built-in`() {
        for (legacy in CleanupPersonas.LEGACY_RETIRED) {
            assertTrue(CleanupPersonas.BUILT_IN.none { it.key == legacy.key })
            assertTrue(!legacy.isBuiltIn)
        }
    }

    @Test fun `legacy retired personas still resolve by key via fromKey`() {
        for (legacy in CleanupPersonas.LEGACY_RETIRED) {
            assertSame(legacy, CleanupPersonas.fromKey(legacy.key))
        }
    }

    // --- Persona list integrity ---

    @Test fun `built-in list contains all five task-oriented personas`() {
        assertEquals(5, CleanupPersonas.BUILT_IN.size)
        assertEquals(
            setOf("formal", "casual", "notes", "email", "concise"),
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

    @Test fun `every built-in persona reports isBuiltIn true`() {
        for (persona in CleanupPersonas.BUILT_IN) {
            assertTrue(persona.isBuiltIn)
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

    // --- Regression guard for #48: selecting a built-in persona had no effect on real output ---
    // MainActivity.selectPrompt used to route an explicit persona tap through resolvePrompt, so a
    // custom prompt saved from an earlier, unrelated "Edit current prompt" edit silently overrode
    // every subsequent persona selection -- "cleanup_style" was saved correctly, but the actual
    // "post_processing_prompt" sent to the model never changed. promptForExplicitSelection is what
    // selectPrompt now calls instead, and must ignore any custom prompt unconditionally.

    @Test fun `promptForExplicitSelection always returns the persona's own prompt`() {
        for (persona in CleanupPersonas.BUILT_IN) {
            assertEquals(persona.prompt, CleanupPersonas.promptForExplicitSelection(persona))
        }
    }

    @Test fun `promptForExplicitSelection ignores a stale custom prompt unlike resolvePrompt`() {
        // Same scenario as "an explicit custom prompt always wins" above, but this is the
        // function an explicit persona tap must use -- the opposite outcome from resolvePrompt.
        val staleCustomPrompt = "Always write in pirate speak."
        for (persona in CleanupPersonas.BUILT_IN) {
            assertEquals(persona.prompt, CleanupPersonas.promptForExplicitSelection(persona))
            assertEquals(staleCustomPrompt, CleanupPersonas.resolvePrompt(persona, staleCustomPrompt))
        }
    }

    // --- currentPersona (#53): shared by MainActivity's Settings picker and the overlay's
    // long-press style menu, so both surfaces always agree on what's "currently selected".

    @Test fun `currentPersona uses the saved key when present`() {
        assertSame(CleanupPersonas.GANGSTER, CleanupPersonas.currentPersona("gangster", currentPrompt = "irrelevant"))
    }

    @Test fun `currentPersona falls back to the default for an unrecognized saved key`() {
        assertSame(CleanupPersonas.DEFAULT, CleanupPersonas.currentPersona("nonsense", currentPrompt = "irrelevant"))
    }

    @Test fun `currentPersona infers from the active prompt when no key is saved`() {
        assertSame(
            CleanupPersonas.NOTES,
            CleanupPersonas.currentPersona(savedKey = null, currentPrompt = CleanupPersonas.NOTES.prompt)
        )
    }

    @Test fun `currentPersona falls back to the default when no key is saved and no prompt matches`() {
        assertSame(
            CleanupPersonas.DEFAULT,
            CleanupPersonas.currentPersona(savedKey = null, currentPrompt = "a fully custom, unmatched prompt")
        )
    }
}
