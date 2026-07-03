package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CleanupStyleTest {

    @Test fun `formal maps to dev prompt`() {
        assertEquals(PostProcessor.DEV_PROMPT, CleanupStyle.FORMAL.prompt)
    }

    @Test fun `casual maps to simple prompt`() {
        assertEquals(PostProcessor.SIMPLE_PROMPT, CleanupStyle.CASUAL.prompt)
    }

    @Test fun `notes maps to structured prompt`() {
        assertEquals(PostProcessor.STRUCTURED_PROMPT, CleanupStyle.NOTES.prompt)
    }

    @Test fun `default style is formal`() {
        assertSame(CleanupStyle.FORMAL, CleanupStyle.DEFAULT)
    }

    @Test fun `fromKey resolves known keys`() {
        assertSame(CleanupStyle.FORMAL, CleanupStyle.fromKey("formal"))
        assertSame(CleanupStyle.CASUAL, CleanupStyle.fromKey("casual"))
        assertSame(CleanupStyle.NOTES, CleanupStyle.fromKey("notes"))
    }

    @Test fun `fromKey falls back to default for unknown or null keys`() {
        assertSame(CleanupStyle.DEFAULT, CleanupStyle.fromKey("nonsense"))
        assertSame(CleanupStyle.DEFAULT, CleanupStyle.fromKey(null))
    }

    @Test fun `resolvePrompt uses the style prompt when there is no custom prompt`() {
        assertEquals(CleanupStyle.CASUAL.prompt, CleanupStyle.resolvePrompt(CleanupStyle.CASUAL, null))
        assertEquals(CleanupStyle.CASUAL.prompt, CleanupStyle.resolvePrompt(CleanupStyle.CASUAL, ""))
        assertEquals(CleanupStyle.CASUAL.prompt, CleanupStyle.resolvePrompt(CleanupStyle.CASUAL, "   "))
    }

    @Test fun `resolvePrompt uses the style prompt when the custom prompt is unedited`() {
        // custom_post_processing_prompt defaults to PostProcessor.DEFAULT_PROMPT until a user
        // actually edits it, so an unedited "custom" prompt must not count as custom.
        assertEquals(
            CleanupStyle.NOTES.prompt,
            CleanupStyle.resolvePrompt(CleanupStyle.NOTES, PostProcessor.DEFAULT_PROMPT)
        )
    }

    @Test fun `an explicit custom prompt always wins over every style`() {
        val custom = "Always write in pirate speak."
        for (style in CleanupStyle.entries) {
            assertEquals(custom, CleanupStyle.resolvePrompt(style, custom))
        }
    }
}
