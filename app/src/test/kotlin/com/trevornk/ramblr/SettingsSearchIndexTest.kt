package com.trevornk.ramblr

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchIndexTest {

    private fun View.rowTitles(): Sequence<String> = sequence {
        val v = this@rowTitles
        if (v is TextView && v.tag == "title") yield(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(v.getChildAt(i).rowTitles())
    }

    /** Every indexed title must exist on the screen it opens (hidden rows count: some only show
     *  once a parent setting is on), so search can't point at a renamed or moved row. */
    @Test fun `every indexed setting exists on its target screen`() {
        val app = RuntimeEnvironment.getApplication()
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val missing = mutableListOf<String>()
        for ((intentKey, group) in SettingsSearchIndex.entries.groupBy { it.open(app).let { i -> i.component!!.className + i.extras?.keySet()?.joinToString { k -> "$k=${i.extras?.get(k)}" } } }) {
            val intent = group.first().open(app)
            @Suppress("UNCHECKED_CAST")
            val type = Class.forName(intent.component!!.className) as Class<Activity>
            val titles = Robolectric.buildActivity(type, intent).setup().get()
                .findViewById<View>(android.R.id.content).rowTitles().toSet()
            group.filter { it.title !in titles }.forEach { missing += "${it.title} ($intentKey)" }
        }
        assertTrue("Indexed but not on screen: $missing", missing.isEmpty())
    }

    private fun entry(title: String, where: String = "", keywords: String = "") =
        SettingsSearchEntry(title, where, keywords) { throw UnsupportedOperationException() }

    @Test fun `every word must match and title hits rank first`() {
        val index = listOf(
            entry("Auto-stop after silence", "Recording", "pause"),
            entry("Silence threshold", "Recording", "pause seconds"),
            entry("App exclusions", "Privacy", "bank"),
        )
        assertEquals(listOf("Silence threshold", "Auto-stop after silence"),
            SettingsSearchIndex.search("silence threshold", index).map { it.title } +
                SettingsSearchIndex.search("silence", index).map { it.title }.filter { it != "Silence threshold" })
        assertEquals(listOf("App exclusions"), SettingsSearchIndex.search("BANK", index).map { it.title })
        assertTrue(SettingsSearchIndex.search("   ", index).isEmpty())
        assertTrue(SettingsSearchIndex.search("bank pause", index).isEmpty())
    }
}
