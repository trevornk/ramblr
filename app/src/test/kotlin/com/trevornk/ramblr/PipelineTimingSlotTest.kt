package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2 (#192): the pipeline-timing lifecycle. The bug being locked out: a dictation ending on a
 * non-happy path (no speech, cancel, watchdog, transcription error) left its timing populated,
 * so the next unrelated injection consumed it and wrote a benchmark line measured from the
 * previous, abandoned dictation's stop tap.
 */
class PipelineTimingSlotTest {

    private fun timing(stopTapAtMs: Long = 1_000L, correlationId: String = "tok-1") =
        PipelineTiming(stopTapAtMs = stopTapAtMs, correlationId = correlationId)

    @Test fun `consume returns the started timing exactly once`() {
        val slot = PipelineTimingSlot()
        val t = timing()
        slot.start(t)
        assertEquals(t, slot.consume())
        assertNull("second consume must return nothing -- consumed exactly once", slot.consume())
    }

    @Test fun `abandon clears the timing so a later injection consumes nothing`() {
        // The cancel/watchdog/no-speech/error scenario: timing was started, the dictation died
        // before finishInjection, and the next injection's consume() must come up empty instead
        // of inheriting the dead dictation's stop-tap anchor.
        val slot = PipelineTimingSlot()
        slot.start(timing(correlationId = "tok-cancelled"))
        slot.abandon()
        assertNull(slot.consume())
    }

    @Test fun `markDrained stamps the drain instant onto the active timing`() {
        val slot = PipelineTimingSlot()
        slot.start(timing(stopTapAtMs = 5_000L))
        slot.markDrained(5_250L)
        assertEquals(5_250L, slot.consume()?.drainAtMs)
    }

    @Test fun `markDrained without an active timing is a no-op`() {
        val slot = PipelineTimingSlot()
        slot.markDrained(9_999L)
        assertNull(slot.consume())
    }

    @Test fun `markDrained after abandon does not resurrect the timing`() {
        // Reader-thread drain racing a main-thread cancel: the drain marker must not bring a
        // dead timeline back to life.
        val slot = PipelineTimingSlot()
        slot.start(timing())
        slot.abandon()
        slot.markDrained(2_000L)
        assertNull(slot.consume())
    }

    @Test fun `a new start replaces whatever was left behind`() {
        val slot = PipelineTimingSlot()
        slot.start(timing(correlationId = "tok-old"))
        val fresh = timing(stopTapAtMs = 42L, correlationId = "tok-new")
        slot.start(fresh)
        assertEquals(fresh, slot.consume())
    }

    @Test fun `abandon on an empty slot is safe`() {
        val slot = PipelineTimingSlot()
        slot.abandon() // must not throw
        assertNull(slot.consume())
    }

    @Test fun `preview flow survives - timing started before a preview is still consumable after it resolves`() {
        // resetToIdle() deliberately does NOT abandon (#115/#40): a previewed dictation resets to
        // idle immediately but its real injectText() runs seconds later on commit/timeout. The
        // slot must hold the timing across that gap; only explicit abandon() drops it.
        val slot = PipelineTimingSlot()
        val t = timing(correlationId = "tok-previewed")
        slot.start(t)
        slot.markDrained(1_100L)
        // ... beginPreview() + resetToIdle() happen here, neither touches the slot ...
        assertEquals(t.copy(drainAtMs = 1_100L), slot.consume())
    }

    @Test fun `slot state checks used above are self-consistent`() {
        val slot = PipelineTimingSlot()
        assertNull(slot.consume())
        slot.start(timing())
        assertFalse(slot.consume() == null)
        assertTrue(slot.consume() == null)
    }
}
