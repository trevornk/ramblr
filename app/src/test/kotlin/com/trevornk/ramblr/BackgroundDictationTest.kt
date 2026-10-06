package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #284: the pure decisions behind background dictation. The daily-driver rule these pin: while the
 * user is still in the field, NOTHING new happens (no clipboard write, no notification, no
 * redirection); every new behavior needs a positive signal that they left.
 */
class BackgroundDictationTest {

    // --- deliver vs clipboard vs notify-only ---

    @Test fun `target still current inserts exactly as before`() {
        assertEquals(BackgroundDelivery.INSERT, backgroundDeliveryFor(targetCurrent = true, excluded = false))
        // Even if the (legacy) funnel will later suppress it for an exclusion, #284 adds nothing.
        assertEquals(BackgroundDelivery.INSERT, backgroundDeliveryFor(targetCurrent = true, excluded = true))
        assertEquals(BackgroundDelivery.INSERT, backgroundDeliveryFor(targetCurrent = true, excluded = false, retentionAllowed = false))
    }

    @Test fun `target gone goes to clipboard and notification, never insertion`() {
        assertEquals(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, backgroundDeliveryFor(targetCurrent = false, excluded = false))
    }

    @Test fun `target gone into an excluded app copies nothing`() {
        assertEquals(BackgroundDelivery.NOTIFY_ONLY_EXCLUDED, backgroundDeliveryFor(targetCurrent = false, excluded = true))
    }

    @Test fun `target gone from a no-retention editor copies and saves nothing`() {
        assertEquals(
            BackgroundDelivery.NOTIFY_ONLY_NOT_RETAINED,
            backgroundDeliveryFor(targetCurrent = false, excluded = false, retentionAllowed = false),
        )
        // Exclusion outranks the retention note: both mean "no clipboard".
        assertEquals(
            BackgroundDelivery.NOTIFY_ONLY_EXCLUDED,
            backgroundDeliveryFor(targetCurrent = false, excluded = true, retentionAllowed = false),
        )
    }

    // --- failure notification gating ---

    @Test fun `failure is only notified when the user cannot see the field`() {
        assertFalse("daily-driver path: no new notification", shouldNotifyBackgroundFailure(targetCurrent = true))
        assertTrue(shouldNotifyBackgroundFailure(targetCurrent = false))
    }

    // --- notice content ---

    @Test fun `no notice for a normal insertion`() {
        assertNull(deliveredNoticeFor(BackgroundDelivery.INSERT, historySaved = true, cleanupFailed = false))
    }

    @Test fun `clipboard notice claims history only when history was actually saved`() {
        val saved = deliveredNoticeFor(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, historySaved = true, cleanupFailed = false)!!
        assertTrue(saved.text.contains("clipboard"))
        assertTrue(saved.text.contains("history"))
        assertTrue("tapping should open history when it holds the text", saved.opensHistory)

        val unsaved = deliveredNoticeFor(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, historySaved = false, cleanupFailed = false)!!
        assertTrue(unsaved.text.contains("clipboard"))
        assertFalse("must not promise a history entry that doesn't exist", unsaved.text.contains("history"))
        assertFalse(unsaved.opensHistory)
    }

    @Test fun `excluded and not-retained notices never claim a clipboard copy`() {
        for (d in listOf(BackgroundDelivery.NOTIFY_ONLY_EXCLUDED, BackgroundDelivery.NOTIFY_ONLY_NOT_RETAINED)) {
            for (saved in listOf(true, false)) {
                val n = deliveredNoticeFor(d, saved, cleanupFailed = false)!!
                assertFalse("$d/$saved: '${n.text}'", n.text.contains("is on the clipboard"))
                assertTrue("$d/$saved: '${n.text}'", n.text.contains("nothing") || n.text.contains("Nothing"))
            }
        }
        assertFalse(deliveredNoticeFor(BackgroundDelivery.NOTIFY_ONLY_NOT_RETAINED, true, false)!!.text.contains("history."))
    }

    @Test fun `cleanup failure is disclosed in the notice`() {
        val n = deliveredNoticeFor(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, historySaved = true, cleanupFailed = true)!!
        assertTrue(n.text.contains("raw transcript"))
    }

    @Test fun `every failure has a notice that opens the main screen and carries no diagnostics`() {
        for (f in BackgroundFailure.values()) {
            val n = failureNoticeFor(f)
            assertTrue(n.title.isNotBlank() && n.text.isNotBlank())
            assertFalse(n.opensHistory)
            assertFalse("no provider/error detail in a notification: '${n.text}'", n.text.contains("HTTP") || n.text.contains("key", ignoreCase = true))
        }
        assertEquals(3, BackgroundFailure.values().size)
    }

    // --- sink ---

    private class SinkProbe {
        val copied = mutableListOf<String>()
        val notices = mutableListOf<BackgroundNotice>()
        val sink = BackgroundResultSink({ copied += it }, { notices += it })
    }

    @Test fun `sink copies then notifies once for a gone target`() {
        val p = SinkProbe()
        val d = p.sink.deliverTargetGone("hello there", historySaved = true, cleanupFailed = false, excluded = false)
        assertEquals(BackgroundDelivery.CLIPBOARD_AND_NOTIFY, d)
        assertEquals(listOf("hello there"), p.copied)
        assertEquals(1, p.notices.size)
        assertFalse("notification must never contain the transcript", p.notices.single().let { "hello there" in it.title || "hello there" in it.text })
    }

    @Test fun `sink never touches the clipboard for exclusion or no-retention`() {
        val p = SinkProbe()
        p.sink.deliverTargetGone("secret", historySaved = true, cleanupFailed = false, excluded = true)
        p.sink.deliverTargetGone("secret", historySaved = false, cleanupFailed = false, excluded = false, retentionAllowed = false)
        assertTrue(p.copied.isEmpty())
        assertEquals(2, p.notices.size)
    }

    @Test fun `sink survives a throwing clipboard or notifier`() {
        val notified = mutableListOf<BackgroundNotice>()
        BackgroundResultSink({ error("clipboard denied") }, { notified += it })
            .deliverTargetGone("x", true, false, excluded = false)
        assertEquals("notification still goes out when the copy failed", 1, notified.size)
        BackgroundResultSink({}, { error("no permission") }).failed(BackgroundFailure.FAILED)
    }

    // --- target capture / currency ---

    @Test fun `capture prefers the focused package and fails open for system surfaces`() {
        assertEquals("com.chat", captureTargetPackage("com.chat", "com.chat"))
        assertEquals("com.chat", captureTargetPackage(null, "com.chat"))
        assertEquals("com.chat", captureTargetPackage("com.chat", null))
        assertNull(captureTargetPackage(null, null))
        assertNull("shade still collapsing from a QS-tile stop must not pin SystemUI", captureTargetPackage("com.android.systemui", null))
    }

    private fun probe(
        captured: String? = "com.chat",
        fg: String? = "com.chat",
        focused: String? = "com.chat",
        locked: Boolean = false,
        field: CapturedFieldState = CapturedFieldState.FOCUSED,
    ) = DeliveryProbe(captured, fg, focused, locked, field)

    @Test fun `staying in the same field is current`() {
        assertTrue(isDeliveryTargetCurrent(probe()))
    }

    @Test fun `nothing captured fails open`() {
        assertTrue(isDeliveryTargetCurrent(probe(captured = null, fg = "com.other", focused = "com.other")))
    }

    @Test fun `a different app holding input focus is gone`() {
        assertFalse(isDeliveryTargetCurrent(probe(fg = "com.mail", focused = "com.mail", field = CapturedFieldState.UNKNOWN)))
    }

    @Test fun `a different field in the same app is gone only on a positive signal`() {
        assertFalse(isDeliveryTargetCurrent(probe(field = CapturedFieldState.MOVED_AWAY)))
        assertTrue("can't tell the fields apart: stay with the old behavior", isDeliveryTargetCurrent(probe(field = CapturedFieldState.UNKNOWN)))
    }

    @Test fun `the device locking is gone`() {
        assertFalse(isDeliveryTargetCurrent(probe(locked = true)))
    }

    @Test fun `nothing focused is current while the same app is in front`() {
        // A tap that stole focus (the #5 empty-scan case) must not read as 'the user left'.
        assertTrue(isDeliveryTargetCurrent(probe(focused = null, field = CapturedFieldState.UNKNOWN)))
    }

    @Test fun `nothing focused but another app in front is gone`() {
        assertFalse(isDeliveryTargetCurrent(probe(fg = "com.launcher", focused = null, field = CapturedFieldState.UNKNOWN)))
    }

    @Test fun `the notification shade in front is not an app switch`() {
        assertTrue(isDeliveryTargetCurrent(probe(fg = "com.android.systemui", focused = null, field = CapturedFieldState.UNKNOWN)))
    }

    @Test fun `unreadable world fails open`() {
        assertTrue(isDeliveryTargetCurrent(probe(fg = null, focused = null, field = CapturedFieldState.UNKNOWN)))
    }

    // --- IME lifecycle ---

    @Test fun `only transcribing work survives a lifecycle loss, and never a destroy`() {
        for (reason in ImeLifecycleLoss.values()) {
            assertEquals("recording is always torn down ($reason)", LifecycleLossAction.TEAR_DOWN, lifecycleLossActionFor(reason, transcribing = false))
        }
        assertEquals(LifecycleLossAction.DETACH_AND_FINISH, lifecycleLossActionFor(ImeLifecycleLoss.INPUT_FINISHED, transcribing = true))
        assertEquals(LifecycleLossAction.DETACH_AND_FINISH, lifecycleLossActionFor(ImeLifecycleLoss.HIDDEN, transcribing = true))
        assertEquals(LifecycleLossAction.TEAR_DOWN, lifecycleLossActionFor(ImeLifecycleLoss.DESTROYED, transcribing = true))
    }

    // --- hold counter ---

    @Test fun `every acquire re-evaluates the start and only the last release stops`() {
        var starts = 0
        var stops = 0
        val holds = BackgroundWorkHolds({ starts++ }, { stops++ })
        holds.acquire(); holds.acquire()
        // The start callback is idempotent; calling it on every acquire is what lets a second
        // dictation revive a service the hard cap/platform timeout stopped under a live hold.
        assertEquals(2, starts)
        holds.release()
        assertEquals(0, stops)
        holds.release()
        assertEquals(1, stops)
        assertEquals(0, holds.held())
    }

    @Test fun `a stray release never goes negative or stops anything`() {
        var stops = 0
        val holds = BackgroundWorkHolds({}, { stops++ })
        holds.release(); holds.release()
        assertEquals(0, stops)
        assertEquals(0, holds.held())
        holds.acquire()
        assertEquals(1, holds.held())
        assertNotNull(holds)
    }
}
