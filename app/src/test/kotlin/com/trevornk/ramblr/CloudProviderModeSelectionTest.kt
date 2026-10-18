package com.trevornk.ramblr

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives the Cloud screen's "Dictation mode" presets the way a user does: pick a preset, flip one
 * per-feature override (the mode becomes Custom, so no preset is checked), then tap the preset
 * again. Regression for the preset un-checking itself under the user's finger: the radios were
 * cleared one by one with `isChecked = false`, which left [RadioGroup]'s own checked id pointing
 * at the cleared button, so re-checking that same button made the group immediately uncheck it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudProviderModeSelectionTest {

    private fun startInCloudMode() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("ramblr", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_local", false).apply()
        CloudFeatureToggle.setCleanupEnabled(RuntimeEnvironment.getApplication(), true)
    }

    private fun View.allDescendants(): Sequence<View> = sequence {
        yield(this@allDescendants)
        if (this@allDescendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).allDescendants())
        }
    }

    private fun View.rowTitled(title: String): View =
        allDescendants().first { (it.findViewWithTag<TextView>("title")?.text?.toString() == title) && it.isClickable }

    @Test fun `re-tapping the previous preset after a per-feature override keeps it checked`() {
        startInCloudMode()
        val activity = Robolectric.buildActivity(CloudProviderActivity::class.java).setup().get()
        val root = activity.window.decorView
        val group = root.allDescendants().filterIsInstance<RadioGroup>().first()
        val cloudPreset = group.allDescendants().filterIsInstance<RadioButton>().first()
        assertTrue("Cloud preset starts checked", cloudPreset.isChecked)

        // Flip cloud cleanup off: transcription stays cloud, cleanup goes local -> Custom.
        root.rowTitled("Cleanup").performClick()

        cloudPreset.performClick()

        assertTrue("Cloud preset must stay checked after the user taps it", cloudPreset.isChecked)
    }

    @Test fun `picking a different preset after a per-feature override checks exactly that preset`() {
        startInCloudMode()
        val activity = Robolectric.buildActivity(CloudProviderActivity::class.java).setup().get()
        val root = activity.window.decorView
        val group = root.allDescendants().filterIsInstance<RadioGroup>().first()
        val presets = group.allDescendants().filterIsInstance<RadioButton>().toList()

        root.rowTitled("Cleanup").performClick()
        presets[2].performClick() // Fastest

        assertTrue("Fastest preset is checked", presets[2].isChecked)
        assertTrue("only one preset is checked", presets.count { it.isChecked } == 1)
    }
}
