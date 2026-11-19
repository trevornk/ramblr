package com.trevornk.ramblr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BubbleShortcutTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clock = ComponentName("com.example.clock", "com.example.clock.Main")

    @Before fun setUp() { app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().apply() }
    @After fun tearDown() { app.getSharedPreferences("ramblr", Context.MODE_PRIVATE).edit().clear().apply() }

    private fun install(component: ComponentName, label: String, exported: Boolean = true) {
        val info = PackageInfo().apply {
            packageName = component.packageName
            applicationInfo = ApplicationInfo().apply { packageName = component.packageName }
            activities = arrayOf(ActivityInfo().apply {
                name = component.className
                packageName = component.packageName
                this.exported = exported
                this.nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply { packageName = component.packageName }
            })
        }
        shadowOf(app.packageManager).installPackage(info)
    }

    @Test fun `off by default - nothing stored and nothing resolved`() {
        assertNull(BubbleShortcut.stored(app))
        assertNull(BubbleShortcut.resolve(app))
    }

    @Test fun `set then resolve returns the explicit component and its label`() {
        install(clock, "Clock")
        BubbleShortcut.set(app, clock)
        val t = BubbleShortcut.resolve(app)!!
        assertEquals(clock, t.component)
        assertEquals("Clock", t.label)
    }

    @Test fun `uninstalled target resolves to null so the menu item is hidden`() {
        BubbleShortcut.set(app, clock) // never installed
        assertNull(BubbleShortcut.resolve(app))
    }

    @Test fun `non-exported target is not offered`() {
        install(clock, "Clock", exported = false)
        BubbleShortcut.set(app, clock)
        assertNull(BubbleShortcut.resolve(app))
    }

    @Test fun `set null clears the setting`() {
        BubbleShortcut.set(app, clock)
        BubbleShortcut.set(app, null)
        assertNull(BubbleShortcut.stored(app))
    }

    @Test fun `garbage stored value parses to null`() {
        assertNull(BubbleShortcut.parse("not a component"))
        assertNull(BubbleShortcut.parse(""))
        assertNull(BubbleShortcut.parse(null))
    }

    @Test fun `launch is explicit, new-task, and carries no data or extras`() {
        install(clock, "Clock")
        assertTrue(BubbleShortcut.launch(app, clock))
        val started: Intent = shadowOf(app).nextStartedActivity
        assertEquals(clock, started.component)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(started.action)
        assertNull(started.data)
        assertNull(started.extras)
    }

    @Test fun `menu logic is untouched - long-press action map ignores the shortcut`() {
        BubbleShortcut.set(app, clock)
        assertEquals(
            OverlayLongPressAction.SHOW_STYLE_MENU,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = false),
        )
        assertEquals(
            OverlayLongPressAction.UNDO_INJECTION,
            overlayLongPressActionFor(RecordingStateMachine.State.IDLE, hasPendingInjection = true),
        )
    }
}
