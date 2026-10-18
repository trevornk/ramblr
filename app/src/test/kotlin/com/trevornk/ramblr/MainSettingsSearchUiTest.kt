package com.trevornk.ramblr

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainSettingsSearchUiTest {

    @Before fun initWorkManager() {
        val app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
    }

    private fun View.all(): Sequence<View> = sequence {
        yield(this@all)
        if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
    }

    private fun View.isShown(): Boolean =
        generateSequence(this) { it.parent as? View }.all { it.visibility == View.VISIBLE }

    @Test fun `opening the app does not focus the search box`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val search = activity.window.decorView.all().filterIsInstance<EditText>().first()
        assertFalse("search box must not grab focus (and the keyboard) on launch", search.isFocused)
    }

    @Test fun `typing finds a setting from another screen and tapping it opens that row`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        root.all().filterIsInstance<EditText>().first().setText("silence")

        val result = root.all().first { v ->
            v.isClickable && v.isShown() && v.findViewWithTag<TextView>("title")?.text?.toString() == "Auto-stop after silence"
        }
        // The grouped list is swapped out while searching.
        assertFalse(root.all().filterIsInstance<TextView>().any { it.text == "Speech and text" && it.isShown() })

        result.performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(BehaviorActivity::class.java.name, started.component?.className)
        assertEquals(BehaviorActivity.Section.DICTATION.name, started.getStringExtra(BehaviorActivity.EXTRA_SECTION))
        assertEquals("Auto-stop after silence", started.getStringExtra(BaseSettingsActivity.EXTRA_HIGHLIGHT_TITLE))
    }

    private fun View.row(title: String): View = all().first { v ->
        v.isClickable && v.findViewWithTag<TextView>("title")?.text?.toString() == title
    }

    @Test fun `main rows open the matching part of a shared screen`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        fun open(title: String) = root.row(title).performClick().let { shadowOf(activity).nextStartedActivity }

        open("Voice keyboard").let {
            assertEquals(InvocationActivity::class.java.name, it.component?.className)
            assertEquals(InvocationActivity.Section.KEYBOARD.name, it.getStringExtra(InvocationActivity.EXTRA_SECTION))
        }
        open("System shortcuts").let {
            assertEquals(InvocationActivity.Section.SHORTCUTS.name, it.getStringExtra(InvocationActivity.EXTRA_SECTION))
        }
        open("History").let {
            assertEquals(DataLogsActivity.Section.HISTORY.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
            assertEquals(true, it.getBooleanExtra(DataLogsActivity.EXTRA_SHOW_HISTORY, false))
        }
        open("Backup & restore").let {
            assertEquals(DataLogsActivity.Section.BACKUP.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
        }
        open("Diagnostics").let {
            assertEquals(DataLogsActivity.Section.DIAGNOSTICS.name, it.getStringExtra(DataLogsActivity.EXTRA_SECTION))
        }
        open("Privacy").let {
            assertEquals(BehaviorActivity.Section.PRIVACY.name, it.getStringExtra(BehaviorActivity.EXTRA_SECTION))
        }
    }

    @Test fun `picking a dictation mode applies the preset and updates the row`() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val row = activity.window.decorView.row("Dictation mode")
        row.performClick()

        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val local = dialog.window!!.decorView.all().filterIsInstance<android.widget.RadioButton>()
            .first { it.text.startsWith("Local") }
        local.performClick()

        assertFalse(dialog.isShowing)
        assertEquals(DictationMode.LOCAL, DictationModePresets.current(app))
        assertEquals(
            DictationModePresets.summary(DictationMode.LOCAL, cleanupOn = PostProcessingToggle.isEnabled(app)),
            row.findViewWithTag<TextView>("subtitle").text.toString(),
        )
    }
}
