package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #322: a long-press on the ring sometimes started recording. These tests pin the gesture
 * classification, including a replay of the real Samsung Z Fold8 finger traces (derived from the
 * ring window's own relayout positions in logcat) that reproduced it.
 */
class RingGestureTrackerTest {

    // Fold8 at its 360 dpi override: 2.25 px/dp; scaled touch slop 8 dp = 18 px.
    private val density = 2.25f
    private val touchSlop = 18
    private val timeoutMs = 500L

    private fun tracker(timeout: Long = timeoutMs) = RingGestureTracker(
        dragSlopPx = RingGestureTracker.dragSlopPx(density, touchSlop),
        postLongPressSlopPx = RingGestureTracker.postLongPressSlopPx(density, touchSlop),
        longPressTimeoutMs = timeout,
    )

    private fun RingGestureTracker.down(armed: Boolean = true, at: Long = 0L) =
        onDown(1000f, 1000f, at, longPressArmed = armed)

    private fun RingGestureTracker.moveBy(dx: Float, dy: Float) = onMove(1000f + dx, 1000f + dy)
    private fun RingGestureTracker.upAt(timeMs: Long, dx: Float = 0f, dy: Float = 0f) =
        onUp(1000f + dx, 1000f + dy, timeMs)

    // ---- constants -------------------------------------------------------------------------

    @Test fun `slops scale with density and never undercut the platform touch slop`() {
        assertEquals(36f, RingGestureTracker.dragSlopPx(2.25f, 18), 0.001f)
        assertEquals(72f, RingGestureTracker.postLongPressSlopPx(2.25f, 18), 0.001f)
        // A hypothetical huge platform slop wins over the dp constant.
        assertEquals(100f, RingGestureTracker.dragSlopPx(1f, 100), 0.001f)
        assertEquals(200f, RingGestureTracker.postLongPressSlopPx(1f, 100), 0.001f)
    }

    @Test fun `long-press time honours a longer system timeout but never shortens below 500`() {
        assertEquals(500L, RingGestureTracker.longPressTimeoutMs(400)) // Pixel default
        assertEquals(500L, RingGestureTracker.longPressTimeoutMs(500)) // Samsung default
        assertEquals(1500L, RingGestureTracker.longPressTimeoutMs(1500)) // accessibility delay
    }

    // ---- basic classification --------------------------------------------------------------

    @Test fun `a quick still touch is a tap`() {
        val t = tracker(); t.down()
        assertEquals(RingGestureOutcome.TAP, t.upAt(120))
    }

    @Test fun `a small wobble during a quick touch is still a tap and never moves the ring`() {
        val t = tracker(); t.down()
        assertEquals(RingMoveResult.HOLDING, t.moveBy(-12f, 6f))
        assertFalse(t.isDragging)
        assertEquals(RingGestureOutcome.TAP, t.upAt(150, -10f, 5f))
    }

    @Test fun `a hold past the long-press time whose timer ran is never a tap`() {
        val t = tracker(); t.down()
        assertTrue(t.onLongPressTimer()); t.markLongPressFired()
        assertEquals(RingGestureOutcome.HOLD_CONSUMED, t.upAt(900))
    }

    @Test fun `a hold past the long-press time with a late timer asks the caller to run it`() {
        val t = tracker(); t.down()
        assertEquals(RingGestureOutcome.HOLD_DUE, t.upAt(520))
    }

    @Test fun `lifting just before the long-press time is still a tap`() {
        val t = tracker(); t.down()
        assertEquals(RingGestureOutcome.TAP, t.upAt(499))
    }

    @Test fun `unarmed recording state keeps a long hold-and-release as a tap that stops`() {
        val t = tracker(); t.down(armed = false)
        assertEquals(RingGestureOutcome.TAP, t.upAt(2_000))
    }

    // ---- drag ------------------------------------------------------------------------------

    @Test fun `crossing the drag slop starts a drag and it is sticky`() {
        val t = tracker(); t.down()
        assertEquals(RingMoveResult.DRAG_STARTED, t.moveBy(-40f, 0f))
        assertEquals(RingMoveResult.DRAGGING, t.moveBy(-10f, 0f))
        // Back at the start point: still a drag, never a tap (the #322 core).
        assertEquals(RingMoveResult.DRAGGING, t.moveBy(0f, 0f))
        assertEquals(RingGestureOutcome.DRAG, t.upAt(300))
    }

    @Test fun `a drag before the timer suppresses the long-press action`() {
        val t = tracker(); t.down()
        t.moveBy(60f, 0f)
        assertFalse(t.onLongPressTimer())
        assertEquals(RingGestureOutcome.DRAG, t.upAt(900))
    }

    @Test fun `a drag after a fired long-press reports it so the menu can be dismissed`() {
        val t = tracker(); t.down()
        assertTrue(t.onLongPressTimer()); t.markLongPressFired()
        assertEquals(RingMoveResult.HOLDING, t.moveBy(-50f, 0f)) // thumb roll < post slop
        assertEquals(RingMoveResult.DRAG_STARTED, t.moveBy(-80f, 0f))
        assertTrue(t.dragStartedAfterLongPress)
        assertEquals(RingGestureOutcome.DRAG, t.upAt(1500))
    }

    @Test fun `a plain drag does not claim to follow a long-press`() {
        val t = tracker(); t.down()
        t.moveBy(60f, 0f)
        assertFalse(t.dragStartedAfterLongPress)
    }

    @Test fun `distance is euclidean not manhattan`() {
        val t = tracker(); t.down()
        // 25/25 is 50 manhattan (would have been a drag before) but only 35.4 euclidean (< 36).
        assertEquals(RingMoveResult.HOLDING, t.moveBy(25f, 25f))
        assertEquals(RingMoveResult.DRAG_STARTED, t.moveBy(26f, 26f))
    }

    @Test fun `cancel mid-drag asks to settle the ring but cancel of a hold does not`() {
        val a = tracker(); a.down(); a.moveBy(80f, 0f)
        assertTrue(a.onCancel())
        val b = tracker(); b.down(); b.moveBy(5f, 0f)
        assertFalse(b.onCancel())
    }

    @Test fun `a new gesture starts from a clean slate`() {
        val t = tracker(); t.down(); t.moveBy(80f, 0f); t.onLongPressTimer(); t.markLongPressFired()
        t.down(at = 5_000)
        assertFalse(t.isDragging); assertFalse(t.longPressFired)
        assertEquals(RingGestureOutcome.TAP, t.upAt(5_100))
    }

    @Test fun `max displacement is tracked for the up log`() {
        val t = tracker(); t.down()
        t.moveBy(30f, 0f); t.moveBy(5f, 0f)
        t.upAt(100, 5f, 0f)
        assertEquals(30f, t.maxDisplacementPx, 0.001f)
    }

    // ---- replay of the real Fold8 traces ---------------------------------------------------

    /** The pre-#322 rule: displacement from DOWN judged only at UP (10 dp Manhattan, 22.5 px here). */
    private fun legacyEndsAsTap(trace: List<Triple<Long, Float, Float>>): Boolean {
        val last = trace.last()
        return kotlin.math.abs(last.second) + kotlin.math.abs(last.third) < 10f * density
    }

    // Traces below are (ms after DOWN, dx px, dy px) of the finger, taken from the ring window's
    // own relayout positions in the Fold8 logcat.
    private class Replay(val tracker: RingGestureTracker, val timerAtMs: Long) {
        var timerRan = false
        var timerFiredAction = false
        var menuDismissed = false
        fun run(trace: List<Triple<Long, Float, Float>>, upAtMs: Long): RingGestureOutcome {
            tracker.onDown(1000f, 1000f, 0L, longPressArmed = true)
            fun maybeTimer(now: Long) {
                if (!timerRan && now >= timerAtMs) {
                    timerRan = true
                    timerFiredAction = tracker.onLongPressTimer()
                    if (timerFiredAction) tracker.markLongPressFired()
                }
            }
            for ((t, dx, dy) in trace) {
                maybeTimer(t)
                val r = tracker.onMove(1000f + dx, 1000f + dy)
                if (r == RingMoveResult.DRAG_STARTED && tracker.dragStartedAfterLongPress) menuDismissed = true
            }
            maybeTimer(upAtMs)
            val last = trace.last()
            return tracker.onUp(1000f + last.second, 1000f + last.third, upAtMs)
        }
    }

    // 22:03:43.781 (menu opened at +551 ms, dismissed 16 ms later, finger wandered 136 px away then
    // came back to 9 px from the start; old logic: tap -> recording).
    private val g1 = listOf(
        Triple(146L,-1f,0f), Triple(163L,-2f,1f), Triple(179L,-3f,1f), Triple(196L,-4f,2f), Triple(213L,-5f,2f), Triple(230L,-6f,3f), Triple(246L,-7f,3f), Triple(263L,-8f,4f), Triple(280L,-9f,4f), Triple(295L,-10f,4f), Triple(312L,-10f,5f), Triple(329L,-11f,5f), Triple(363L,-12f,5f), Triple(396L,-13f,5f), Triple(430L,-14f,5f), Triple(462L,-15f,5f), Triple(496L,-16f,5f), Triple(602L,-17f,5f), Triple(610L,-18f,5f), Triple(611L,-19f,5f), Triple(679L,-20f,5f), Triple(746L,-21f,5f), Triple(796L,-22f,5f), Triple(880L,-23f,5f), Triple(962L,-24f,5f), Triple(1313L,-24f,6f), Triple(1330L,-24f,7f), Triple(1346L,-25f,7f), Triple(1363L,-25f,8f), Triple(1379L,-26f,8f), Triple(1396L,-26f,9f), Triple(1413L,-28f,12f), Triple(1430L,-32f,19f), Triple(1446L,-34f,28f), Triple(1463L,-36f,36f), Triple(1480L,-38f,43f), Triple(1496L,-39f,47f), Triple(1513L,-40f,51f), Triple(1530L,-41f,53f), Triple(1546L,-44f,56f), Triple(1563L,-50f,60f), Triple(1580L,-55f,64f), Triple(1596L,-60f,66f), Triple(1613L,-62f,68f), Triple(1630L,-64f,69f), Triple(1647L,-65f,70f), Triple(1664L,-66f,71f), Triple(1680L,-67f,71f), Triple(1696L,-68f,72f), Triple(1713L,-69f,72f), Triple(1763L,-68f,71f), Triple(1780L,-62f,66f), Triple(1796L,-54f,61f), Triple(1813L,-47f,55f), Triple(1829L,-40f,47f), Triple(1846L,-34f,38f), Triple(1863L,-30f,29f), Triple(1880L,-27f,23f), Triple(1896L,-25f,18f), Triple(1913L,-22f,13f), Triple(1929L,-19f,10f), Triple(1946L,-17f,8f), Triple(1963L,-15f,6f), Triple(1979L,-14f,5f), Triple(1996L,-13f,4f), Triple(2030L,-13f,3f), Triple(2079L,-8f,5f), Triple(2298L,0f,9f)
    )

    // 22:03:49.143 (slow roll to -34 px over 1.1 s, no menu opened, flick back, tap -> recording).
    private val g2 = listOf(
        Triple(150L,-3f,0f), Triple(167L,-6f,0f), Triple(183L,-8f,0f), Triple(201L,-9f,0f), Triple(217L,-11f,0f), Triple(234L,-12f,0f), Triple(251L,-13f,0f), Triple(284L,-14f,0f), Triple(301L,-16f,0f), Triple(334L,-17f,0f), Triple(351L,-18f,0f), Triple(368L,-19f,0f), Triple(384L,-20f,0f), Triple(401L,-21f,0f), Triple(434L,-22f,0f), Triple(451L,-23f,0f), Triple(466L,-24f,0f), Triple(516L,-25f,0f), Triple(550L,-26f,0f), Triple(567L,-27f,0f), Triple(617L,-28f,0f), Triple(650L,-29f,0f), Triple(684L,-30f,0f), Triple(717L,-31f,0f), Triple(751L,-32f,0f), Triple(784L,-33f,0f), Triple(884L,-34f,0f), Triple(1100L,-18f,0f), Triple(1136L,-3f,0f)
    )

    // 22:03:52.689 (menu opened +507 ms, dismissed +704 ms by roll, lifted 14 px from start).
    private val g3 = listOf(
        Triple(138L,-1f,0f), Triple(155L,-2f,1f), Triple(172L,-3f,2f), Triple(188L,-4f,2f), Triple(204L,-5f,3f), Triple(238L,-6f,4f), Triple(272L,-7f,5f), Triple(305L,-8f,6f), Triple(338L,-9f,6f), Triple(371L,-10f,6f), Triple(421L,-11f,6f), Triple(438L,-12f,6f), Triple(472L,-13f,6f), Triple(535L,-14f,6f), Triple(604L,-15f,6f), Triple(671L,-16f,6f), Triple(755L,-17f,6f), Triple(921L,-13f,6f), Triple(953L,-12f,6f)
    )

    @Test fun `the old rule would have tapped every one of the three reported gestures`() {
        assertTrue(legacyEndsAsTap(g1))
        assertTrue(legacyEndsAsTap(g2))
        assertTrue(legacyEndsAsTap(g3))
    }

    @Test fun `Fold trace 1 - hold that wanders off and returns is a drag not a tap`() {
        val r = Replay(tracker(), timerAtMs = 551)
        val outcome = r.run(g1, upAtMs = 2318)
        assertTrue("long-press fired at 551 ms", r.timerFiredAction)
        assertTrue("wandering far dismisses the menu as a drag (#57)", r.menuDismissed)
        assertEquals(RingGestureOutcome.DRAG, outcome)
    }

    @Test fun `Fold trace 2 - slow thumb roll keeps the long-press and never becomes a tap`() {
        val r = Replay(tracker(), timerAtMs = 551)
        val outcome = r.run(g2, upAtMs = 1156)
        assertTrue("long-press must fire despite 26 px roll at 551 ms", r.timerFiredAction)
        assertFalse(r.tracker.isDragging)
        assertEquals(RingGestureOutcome.HOLD_CONSUMED, outcome)
    }

    @Test fun `Fold trace 3 - menu survives thumb roll and lift is not a tap`() {
        val r = Replay(tracker(), timerAtMs = 507)
        val outcome = r.run(g3, upAtMs = 973)
        assertTrue(r.timerFiredAction)
        assertFalse("menu must not be dismissed by 18 px of roll", r.menuDismissed)
        assertEquals(RingGestureOutcome.HOLD_CONSUMED, outcome)
    }
}
