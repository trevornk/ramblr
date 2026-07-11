package com.trevornk.ramblr

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * "Updates" settings screen (Part 3 of the GitHub self-update feature, github distribution
 * flavor only). Lives in src/github/kotlin/ (not src/main/) for the same Google Play policy
 * reason as [SelfUpdateChecker]/[SelfUpdateResolver] -- see [SelfUpdateChecker]'s AGENTS note.
 * This is why it's a wholly separate Activity rather than added directly into the shared
 * [AdvancedActivity]: a flavor source set can't *override* an existing main-source Activity
 * class in this Gradle/AGP setup (there's exactly one compiled class per fully-qualified name
 * per variant; "extending" it would mean either duplicating AdvancedActivity's UI-building code
 * here or moving AdvancedActivity itself into src/github/, which would remove it from storefront
 * entirely -- wrong, since everything else on that screen applies to both flavors). A new,
 * storefront-absent Activity is the only approach where the extra section is *only* ever compiled
 * into the github flavor, matching the same physical-absence requirement SelfUpdateChecker.kt's
 * kdoc already establishes for this whole feature. [AdvancedActivity] still gets one entry-point
 * row into this screen, added via reflection (`Class.forName`) rather than a direct
 * `SelfUpdateSettingsActivity::class.java` reference, since a compile-time reference from
 * src/main/ code would fail to compile for the storefront flavor (its compile task never sees
 * this class at all).
 */
class SelfUpdateSettingsActivity : BaseSettingsActivity() {

    private lateinit var notifySwitch: MaterialSwitch
    private lateinit var autoInstallSwitch: MaterialSwitch
    private lateinit var autoInstallGroup: NestedGroup
    private lateinit var statusRowSub: TextView
    private lateinit var checkNowRow: LinearLayout

    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Updates"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        // --- Notify toggle ---
        notifySwitch = MaterialSwitch(this).apply {
            isChecked = SelfUpdatePrefs.isNotifyEnabled(this@SelfUpdateSettingsActivity)
            isClickable = false
        }
        root.addView(settingsRow(
            "Notify me of updates",
            "Periodically checks GitHub for a newer release and lets you know",
            notifySwitch
        ) { onNotifyToggle(!notifySwitch.isChecked) })

        // --- Auto-install toggle, nested under (and depends on) the notify toggle above ---
        autoInstallSwitch = MaterialSwitch(this).apply {
            isChecked = SelfUpdatePrefs.isAutoInstallEnabled(this@SelfUpdateSettingsActivity)
            isClickable = false
        }
        autoInstallGroup = nestedGroup()
        autoInstallGroup.content.addView(settingsRow(
            "Automatically install updates",
            "Installs a new release as soon as it's found, without asking first",
            autoInstallSwitch
        ) {
            val newVal = !autoInstallSwitch.isChecked
            SelfUpdatePrefs.setAutoInstallEnabled(this, newVal)
            autoInstallSwitch.isChecked = newVal
        })
        root.addView(autoInstallGroup.outer)

        // --- Status row (live, from SelfUpdateChecker's cached state -- no hardcoded copy) ---
        val statusRow = settingsRow("Status", "")
        statusRowSub = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Manual one-off check, exercising the whole pipeline before Part 5's periodic job exists ---
        checkNowRow = settingsRow("Check now", "Check GitHub for a newer release right away") { onCheckNow() }
        root.addView(checkNowRow)

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

    private fun onNotifyToggle(enabled: Boolean) {
        SelfUpdatePrefs.setNotifyEnabled(this, enabled)
        notifySwitch.isChecked = enabled
        refresh()
    }

    private fun refresh() {
        val notifyEnabled = SelfUpdatePrefs.isNotifyEnabled(this)
        notifySwitch.isChecked = notifyEnabled
        autoInstallSwitch.isChecked = SelfUpdatePrefs.isAutoInstallEnabled(this)
        // Can't auto-install without checking in the first place -- visually/logically depends
        // on the notify toggle (per the Part 3 spec), shown/hidden via the same nestedGroup
        // mechanism AdvancedActivity's dictation-history sub-row uses.
        autoInstallGroup.outer.visibility = if (notifyEnabled) android.view.View.VISIBLE else android.view.View.GONE
        refreshStatusRow()
    }

    private fun refreshStatusRow() {
        val result = SelfUpdateChecker.cachedResult(this)
        val lastCheckedAtMs = SelfUpdateChecker.lastCheckedAtMs(this)
        statusRowSub.text = SelfUpdateStatusFormatter.subtitle(
            result = result,
            lastCheckedAtMs = lastCheckedAtMs,
            nowMs = System.currentTimeMillis(),
            runningVersionName = BuildConfig.VERSION_NAME,
            runningVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    /** Triggers a real, one-off check via [SelfUpdateChecker.check] (a blocking network call, so
     *  off the main thread -- mirrors [AdvancedActivity]'s `thread { }` pattern for the overlay
     *  icon picker) and updates the status row with the real result. Also posts the
     *  update-available notification right away if one is found and notifications are enabled,
     *  so this one row exercises the entire notify pipeline end to end even before Part 5's
     *  periodic job exists. */
    private fun onCheckNow() {
        if (checking) return
        checking = true
        checkNowRow.isEnabled = false
        statusRowSub.text = "Checking…"
        val appContext = applicationContext
        thread {
            val result = SelfUpdateChecker.check(appContext, forceFresh = true)
            runOnUiThread {
                checking = false
                checkNowRow.isEnabled = true
                if (isDestroyed || isFinishing) return@runOnUiThread
                refreshStatusRow()
                if (result is UpdateCheckResult.UpdateAvailable && SelfUpdatePrefs.isNotifyEnabled(this)) {
                    SelfUpdateNotifications.postUpdateAvailable(this, result)
                }
                toast(SelfUpdateStatusFormatter.statusLine(result))
            }
        }
    }
}
