package com.trevornk.ramblr

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The privacy contract for "Show the icon": the default ALWAYS mode keeps exactly the subscription
 * the static XML declares and handles no events; only the opt-in modes add window/focus events,
 * and they are dropped again when the user switches back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingVisibilityEventsTest {

    private val staticTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED

    // --- the subscription decision ---

    @Test fun `always adds no event types to the static config`() {
        assertEquals(staticTypes, RingEventTypes.forMode(staticTypes, RingVisibilityMode.ALWAYS))
    }

    @Test fun `keyboard and text field modes add window and focus types`() {
        for (mode in listOf(RingVisibilityMode.KEYBOARD, RingVisibilityMode.TEXT_FIELD)) {
            val types = RingEventTypes.forMode(staticTypes, mode)
            assertTrue(types and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
            assertTrue(types and AccessibilityEvent.TYPE_VIEW_FOCUSED != 0)
        }
    }

    @Test fun `the opt-in modes add nothing beyond window and focus changes`() {
        val extra = RingEventTypes.forMode(0, RingVisibilityMode.KEYBOARD)
        assertEquals(AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED, extra)
    }

    @Test fun `always ignores every event, other modes only window and focus`() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
            assertFalse(RingEventTypes.isRelevant(RingVisibilityMode.ALWAYS, type))
            assertTrue(RingEventTypes.isRelevant(RingVisibilityMode.KEYBOARD, type))
            assertTrue(RingEventTypes.isRelevant(RingVisibilityMode.TEXT_FIELD, type))
        }
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            for (mode in RingVisibilityMode.entries) assertFalse(RingEventTypes.isRelevant(mode, type))
        }
    }

    // --- applying it to the live service info ---

    private class FakeServiceInfo(types: Int) {
        var info: AccessibilityServiceInfo? = AccessibilityServiceInfo().apply { eventTypes = types }
        var writes = 0
        val subscription = RingEventSubscription(getInfo = { info }, setInfo = { info = it; writes++ })
    }

    @Test fun `always on a fresh service leaves the subscription untouched and writes nothing`() {
        val fake = FakeServiceInfo(staticTypes)
        assertFalse(fake.subscription.apply(RingVisibilityMode.ALWAYS))
        assertEquals(staticTypes, fake.info!!.eventTypes)
        assertEquals(0, fake.writes)
    }

    @Test fun `switching to a gated mode widens the subscription and back to always restores it exactly`() {
        val fake = FakeServiceInfo(staticTypes)
        assertTrue(fake.subscription.apply(RingVisibilityMode.KEYBOARD))
        assertTrue(fake.info!!.eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
        // KEYBOARD -> TEXT_FIELD: already subscribed, no rewrite.
        assertFalse(fake.subscription.apply(RingVisibilityMode.TEXT_FIELD))
        assertEquals(1, fake.writes)
        assertTrue(fake.subscription.apply(RingVisibilityMode.ALWAYS))
        assertEquals(staticTypes, fake.info!!.eventTypes)
    }

    @Test fun `an unbound service is a no-op`() {
        val fake = FakeServiceInfo(staticTypes).apply { info = null }
        assertFalse(fake.subscription.apply(RingVisibilityMode.KEYBOARD))
    }

    // --- the stale-state bug ---

    @Test fun `tracker only asks for a relayout when the answer changes`() {
        val t = RingAnchorTracker()
        assertTrue(t.update(true))
        assertFalse(t.update(true))
        assertTrue(t.update(false))
    }

    @Test fun `reset makes the next answer count even if it equals the stale one`() {
        val t = RingAnchorTracker()
        t.update(true)
        t.reset()
        assertNull(t.lastPresent())
        assertTrue(t.update(true))
    }

    // --- wiring in the real service class ---

    private fun service() = Robolectric.buildService(WhisperAccessibilityService::class.java, null).create().get()

    @Test fun `a mode change in the service resets the cached anchor`() {
        val s = service()
        s.ringAnchorTracker.update(true) // cached under the previous mode
        RingVisibilityMode.write(RuntimeEnvironment.getApplication(), RingVisibilityMode.TEXT_FIELD)
        s.onRingVisibilityModeChanged()
        assertNull(s.ringAnchorTracker.lastPresent())
    }

    @Test fun `the service reacts live to the saved mode changing`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        val s = service()
        s.startObservingRingMode()
        s.ringAnchorTracker.update(false)
        RingVisibilityMode.write(app, RingVisibilityMode.KEYBOARD)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(s.ringAnchorTracker.lastPresent())
    }

    @Test fun `in always mode the service handler ignores window events entirely`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().commit()
        val s = service()
        s.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        s.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        assertNull(s.ringAnchorTracker.lastPresent())
    }

    @Test fun `in a gated mode a window event is handled`() {
        val app = RuntimeEnvironment.getApplication()
        RingVisibilityMode.write(app, RingVisibilityMode.KEYBOARD)
        val s = service()
        s.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertTrue(s.ringAnchorTracker.lastPresent() != null)
    }

    // --- static configs stay as on main ---

    private fun xml(name: String) = File("src/main/res/xml/$name").readText()

    @Test fun `static configs subscribe to focus only and never to window changes`() {
        for (f in listOf("accessibility_service_config.xml", "accessibility_service_config_system.xml")) {
            val text = xml(f)
            assertTrue(f, text.contains("android:accessibilityEventTypes=\"typeViewFocused\""))
            assertFalse(f, text.contains("typeWindowsChanged"))
        }
    }

    @Test fun `both configs report view ids so resolveRealText can read viewIdResourceName`() {
        // Without flagReportViewIds, viewIdResourceName is null in the real service and the
        // WhatsApp search-placeholder rule never matches.
        for (f in listOf("accessibility_service_config.xml", "accessibility_service_config_system.xml")) {
            val flags = Regex("android:accessibilityFlags=\"([^\"]*)\"")
                .find(xml(f))!!.groupValues[1]
            assertTrue(f, flags.split('|').contains("flagReportViewIds"))
        }
    }

    @Test fun `the floating icon service never requests the accessibility button flag`() {
        // flagRequestAccessibilityButton reclassifies a service as an invisible-toggle on newer
        // Android; only the opt-in system-controls twin may carry it.
        // (the config's comments mention the flag by name, so look at the attribute itself)
        val flags = Regex("android:accessibilityFlags=\"([^\"]*)\"")
            .find(xml("accessibility_service_config.xml"))!!.groupValues[1]
        assertFalse(flags, flags.contains("flagRequestAccessibilityButton"))
        val src = File("src/main/kotlin/com/trevornk/ramblr/WhisperAccessibilityService.kt").readText()
        assertFalse(src.contains("FLAG_REQUEST_ACCESSIBILITY_BUTTON"))
    }
}
