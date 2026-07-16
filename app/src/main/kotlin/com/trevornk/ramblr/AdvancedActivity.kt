package com.trevornk.ramblr

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Advanced" category screen (#93 restructure, updated #95 Phase 3, restructured again #104):
 * a slim category list mirroring MainActivity's own top-level pattern exactly, rather than one
 * long scrolling list of every advanced setting. "Redo setup walkthrough" stays as its own
 * top-level row (a one-off action, not a settings area) -- everything else previously here now
 * lives in its own sub-Activity:
 *  - [OverlayAppearanceActivity]: icon size, border/fill/glyph color, custom icon
 *  - [BehaviorActivity]: debug toggle, per-app persona, hide-icon, auto-hide, raw-text-retry, vocabulary
 *  - Updates: the pre-existing github-flavor-only SelfUpdateSettingsActivity (Class.forName-gated,
 *    see [selfUpdateSettingsActivityClass]'s kdoc for why)
 *  - [DataLogsActivity]: dictation history, backup/restore (#103), benchmark/quality log export
 *
 * See git history before #104 for the single-list version this replaced.
 */
class AdvancedActivity : BaseSettingsActivity() {

    private lateinit var overlayAppearanceRowSub: TextView
    private lateinit var behaviorRowSub: TextView
    private lateinit var dataLogsRowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Advanced"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        root.addView(settingsRow(
            "Redo setup walkthrough",
            "Re-run the guide for permissions, transcription, cleanup, and streaming preview"
        ) { startWalkthroughInMainActivity() })

        root.addView(sectionHeader("Settings"))

        val overlayAppearanceRow = settingsRow("Overlay Appearance", OverlayAppearanceActivity.subtitle(this)) {
            startActivity(Intent(this, OverlayAppearanceActivity::class.java))
        }
        overlayAppearanceRowSub = overlayAppearanceRow.findViewWithTag("subtitle")
        root.addView(overlayAppearanceRow)

        val behaviorRow = settingsRow("Behavior", BehaviorActivity.subtitle(this)) {
            startActivity(Intent(this, BehaviorActivity::class.java))
        }
        behaviorRowSub = behaviorRow.findViewWithTag("subtitle")
        root.addView(behaviorRow)

        // Self-update (Part 3, github distribution flavor only): SelfUpdateSettingsActivity
        // lives in src/github/kotlin/, so this Activity (src/main/, compiled into every flavor)
        // can't reference it by class literal -- that reference would fail to resolve at compile
        // time for the storefront flavor, whose compile task never sees that file at all (see
        // SelfUpdateSettingsActivity's own kdoc for the full reasoning). Class.forName is the
        // one way to conditionally launch a flavor-only Activity from shared code: it resolves
        // at runtime, so it simply throws-and-is-caught (row never added) on storefront, and
        // succeeds on github. android/src/github/AndroidManifest.xml registers the Activity only
        // for the github flavor's manifest merge, so it's also never launchable outside it even
        // if this lookup somehow succeeded in the wrong build.
        selfUpdateSettingsActivityClass()?.let { activityClass ->
            root.addView(settingsRow("Updates", "Check for and manage app updates") {
                startActivity(Intent(this, activityClass))
            })
        }

        val dataLogsRow = settingsRow("Data & Logs", DataLogsActivity.subtitle(this)) {
            startActivity(Intent(this, DataLogsActivity::class.java))
        }
        dataLogsRowSub = dataLogsRow.findViewWithTag("subtitle")
        root.addView(dataLogsRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        overlayAppearanceRowSub.text = OverlayAppearanceActivity.subtitle(this)
        behaviorRowSub.text = BehaviorActivity.subtitle(this)
        dataLogsRowSub.text = DataLogsActivity.subtitle(this)
    }

    /** Navigates back to MainActivity and asks it to (re-)start the onboarding wizard once
     *  resumed (#93): onboarding dialogs stay owned by MainActivity rather than being duplicated
     *  or moved here, so this is a plain intent hand-off, not a re-implementation. */
    private fun startWalkthroughInMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_START_WALKTHROUGH, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /** Resolves `com.trevornk.ramblr.SelfUpdateSettingsActivity` reflectively, returning null
     *  (and thus hiding the row) on storefront builds where that class simply doesn't exist in
     *  the compiled classes. See the call site's kdoc for why this can't be a direct reference. */
    @Suppress("UNCHECKED_CAST")
    private fun selfUpdateSettingsActivityClass(): Class<out android.app.Activity>? = try {
        Class.forName("com.trevornk.ramblr.SelfUpdateSettingsActivity") as Class<out android.app.Activity>
    } catch (_: ClassNotFoundException) {
        null
    }

    companion object {
        /** Category subtitle for MainActivity's Advanced row (#93, updated #104) -- unlike the
         *  other four categories there's no single on/off/mode signal worth summarizing here
         *  (this screen is redo-setup + four subsections), so this just names what's inside
         *  rather than picking one value. */
        fun subtitle(context: android.content.Context): String =
            "Redo setup, overlay appearance, behavior, updates, data & logs"
    }
}
