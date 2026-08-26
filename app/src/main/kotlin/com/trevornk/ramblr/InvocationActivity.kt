package com.trevornk.ramblr

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * "Invocation" settings screen (#156): one place to see and choose every way of starting a
 * dictation -- the app-owned floating ring and QS tile, and the OS-owned accessibility
 * button/gesture and volume-keys hold.
 *
 * The OS-owned rows exist because of the invisible-toggle trap this screen is really about
 * (#156 memo): Ramblr is an INVISIBLE_TOGGLE-class service, so system Settings couples the
 * service's on/off state to its shortcut bindings -- turning off the LAST "Ramblr shortcut"
 * switch there also turns off Ramblr itself. The app cannot manage those bindings through any
 * supported API (enableShortcutsForTargets is @hide + MANAGE_ACCESSIBILITY), so the base tier
 * deep-links to the service's own system page (ACTION_ACCESSIBILITY_DETAILS_SETTINGS, API 30+ =
 * minSdk) BEHIND an explainer dialog that names the trap before the user meets it.
 *
 * The optional advanced tier (WRITE_SECURE_SETTINGS via a one-time adb grant, feature-detected
 * per tap) upgrades the OS-owned rows to real in-app toggles: raw Settings.Secure writes bypass
 * the invisible-toggle sync entirely (device-verified), so bindings change without the Settings
 * round-trip and without ever tripping the service kill. See [InvocationSecureSettings].
 */
class InvocationActivity : BaseSettingsActivity() {

    private lateinit var ringSwitch: MaterialSwitch
    private lateinit var ringRowSub: TextView
    private lateinit var systemButtonRowSub: TextView
    private lateinit var volumeKeysRowSub: TextView
    private lateinit var advancedTierRowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Invocation"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        root.addView(sectionHeader("How to start dictation"))

        // --- Floating ring (app-owned): a direct toggle over IconHiddenState, the same flag the
        // long-press "Hide icon" menu and BehaviorActivity's restore row already share.
        ringSwitch = MaterialSwitch(this).apply { isClickable = false }
        val ringRow = settingsRow("Floating ring", "Checking...", ringSwitch) {
            val nowHidden = !IconHiddenState.isHidden(this)
            IconHiddenState.setHidden(this, nowHidden)
            if (nowHidden) {
                // Same affordance chain as the long-press hide (#135): once the ring is gone
                // the notification is the way back that doesn't require finding this screen.
                IconVisibilityNotifications.postHidden(this)
            } else {
                IconVisibilityNotifications.cancel(this)
            }
            WhisperAccessibilityService.instance?.applyOverlayVisibility()
            refresh()
        }
        ringRowSub = ringRow.findViewWithTag("subtitle")
        root.addView(ringRow)

        // --- System accessibility button / gesture (OS-owned).
        val systemButtonRow = settingsRow("System accessibility button / gesture", "Checking...") {
            onOsShortcutRowTapped(
                key = InvocationSecureSettings.KEY_BUTTON_TARGETS,
                currentlyBound = InvocationSecureSettings.isButtonTargetBound(this),
                methodName = "system accessibility button",
                deepLinkHowTo = "On the next screen, tap the \u201cRamblr shortcut\u201d switch to " +
                    "turn the button on or off.\n\n" +
                    "Heads up: the system's floating button has its own long-press/drag menu and " +
                    "a bright \u201cselected\u201d look \u2014 that brightness is a system affordance, " +
                    "NOT Ramblr recording.",
            )
        }
        systemButtonRowSub = systemButtonRow.findViewWithTag("subtitle")
        root.addView(systemButtonRow)

        // --- Volume-keys hold (OS-owned). Note (#156 memo, AMS 4295-4331): for a button-flag
        // service this shortcut can only ever ENABLE the service or fire the dictation callback,
        // never disable it -- it's the one OS entry point with no foot-gun of its own.
        val volumeKeysRow = settingsRow("Volume-keys hold", "Checking...") {
            onOsShortcutRowTapped(
                key = InvocationSecureSettings.KEY_SHORTCUT_TARGET_SERVICE,
                currentlyBound = InvocationSecureSettings.isVolumeKeysBound(this),
                methodName = "volume-keys shortcut",
                deepLinkHowTo = "On the next screen, tap \u201cRamblr shortcut\u201d, then tick " +
                    "\u201cHold volume keys\u201d.\n\n" +
                    "The first time you use the shortcut, Android shows a one-time confirmation " +
                    "dialog \u2014 choose \u201cTurn on\u201d there. Holding both volume keys then " +
                    "starts or stops dictation from anywhere.",
            )
        }
        volumeKeysRowSub = volumeKeysRow.findViewWithTag("subtitle")
        root.addView(volumeKeysRow)

        // --- QS tile (app-owned, #127). The OS never tells an app whether its tile is currently
        // placed in the panel, so this row is an add-affordance, not a status readout.
        root.addView(settingsRow(
            "Quick Settings tile",
            invocationQsTileSubtitleText(canRequestAdd = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ) { onQsTileRowTapped() })

        root.addView(sectionHeader("Advanced"))

        val advancedTierRow = settingsRow("Direct shortcut control", "Checking...") {
            showAdvancedTierDialog()
        }
        advancedTierRowSub = advancedTierRow.findViewWithTag("subtitle")
        root.addView(advancedTierRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // The OS-owned states change out from under us exactly when this screen is most relevant
        // (the user just came back from the system Settings round-trip) -- re-read everything.
        refresh()
    }

    private fun refresh() {
        val ringVisible = !IconHiddenState.isHidden(this)
        val direct = InvocationSecureSettings.canWrite(this)
        ringSwitch.isChecked = ringVisible
        ringRowSub.text = invocationRingSubtitleText(ringVisible)
        systemButtonRowSub.text = invocationSystemButtonSubtitleText(
            bound = InvocationSecureSettings.isButtonTargetBound(this),
            directControl = direct,
        )
        volumeKeysRowSub.text = invocationVolumeKeysSubtitleText(
            bound = InvocationSecureSettings.isVolumeKeysBound(this),
            directControl = direct,
        )
        advancedTierRowSub.text = invocationAdvancedTierSubtitleText(granted = direct)
    }

    /**
     * OS-owned row tap: with the advanced tier granted, flip the binding in-app via a raw Secure
     * write (which bypasses the invisible-toggle service kill entirely -- #156 memo §3); without
     * it, explain the trap FIRST, then deep-link to the service's own system Settings page.
     * The write path silently degrades to the deep-link path on failure ([setBinding] returns
     * false rather than throwing).
     */
    private fun onOsShortcutRowTapped(key: String, currentlyBound: Boolean, methodName: String, deepLinkHowTo: String) {
        if (InvocationSecureSettings.canWrite(this)) {
            if (InvocationSecureSettings.setBinding(this, key, bound = !currentlyBound)) {
                refresh()
                return
            }
            toast("Direct write failed — opening system settings instead")
        }
        showOsShortcutExplainerThenDeepLink(methodName, deepLinkHowTo)
    }

    /**
     * The #156 explainer, shown BEFORE navigating: the trap is that the system page's "Ramblr
     * shortcut" switch doubles as a service kill-switch when it's the last shortcut left, and
     * nothing on that page says so.
     */
    private fun showOsShortcutExplainerThenDeepLink(methodName: String, howTo: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Before you go: how this switch behaves")
            .setMessage(
                "The $methodName is managed on Ramblr's system Accessibility page.\n\n$howTo\n\n" +
                    "\u26a0\ufe0f Important: the \u201cRamblr shortcut\u201d switch controls where the " +
                    "shortcut appears \u2014 but turning off the LAST shortcut also turns off " +
                    "Ramblr itself (system behavior for always-on accessibility tools). To hide " +
                    "the system button without turning Ramblr off, enable the floating ring on " +
                    "this screen first."
            )
            .setPositiveButton("Open system settings") { _, _ -> openServiceDetailsSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Deep-link to Ramblr's own service page (action string public-in-behavior since API 30 =
     *  minSdk; see [InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS] for why it's
     *  a spelled-out string). Falls back to the top-level Accessibility list if an OEM skin
     *  doesn't resolve the details action. */
    private fun openServiceDetailsSettings() {
        val component = ComponentName(this, WhisperAccessibilityService::class.java)
        val details = Intent(InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        try {
            startActivity(details)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /** API 33+ gets the system's one-tap "add this tile?" sheet; below that, spell out the
     *  manual panel-edit steps. */
    private fun onQsTileRowTapped() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBar = getSystemService(StatusBarManager::class.java)
            if (statusBar != null) {
                statusBar.requestAddTileService(
                    ComponentName(this, RamblrQsTileService::class.java),
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_mic),
                    mainExecutor,
                ) { /* result codes don't distinguish states we can act on; refresh is moot since tile placement isn't readable */ }
                return
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add the Ramblr tile")
            .setMessage(
                "Swipe down twice to open Quick Settings, tap the edit (pencil) button, then " +
                    "drag the Ramblr tile into your panel. Tapping the tile starts or stops dictation."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** The advanced tier's explainer: what it unlocks, the exact adb command (selectable for
     *  copy), and the live granted/not-granted state. */
    private fun showAdvancedTierDialog() {
        val granted = InvocationSecureSettings.canWrite(this)
        val command = wssAdbCommand(packageName)
        val message = TextView(this).apply {
            text = (if (granted) "\u2705 Active. Shortcut switches on this screen now apply " +
                "instantly, in-app, and can never trip the system's \u201clast shortcut off turns " +
                "the service off\u201d behavior.\n\n"
            else "Android only lets apps change system shortcut bindings with a permission that " +
                "must be granted once over adb (it survives reboots, but not reinstalls):\n\n") +
                "$command\n\n" +
                "With it granted, the rows above switch in-app with no system Settings round-trip, " +
                "and if the service ever gets turned off by the system switch, Ramblr can turn " +
                "itself back on with one tap."
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Direct shortcut control")
            .setView(message)
            .setPositiveButton("OK") { _, _ -> refresh() }
            .show()
    }

    companion object {
        /** MainActivity category-row subtitle (SettingsSubtitles pattern: shared with refresh). */
        fun subtitle(context: android.content.Context): String = invocationMainRowSubtitleText(
            ringVisible = !IconHiddenState.isHidden(context),
            systemButtonBound = InvocationSecureSettings.isButtonTargetBound(context),
            volumeKeysBound = InvocationSecureSettings.isVolumeKeysBound(context),
        )
    }
}
