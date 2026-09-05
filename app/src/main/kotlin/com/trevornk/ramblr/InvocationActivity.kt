package com.trevornk.ramblr

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * "Invocation" settings screen (#156, dual-component rework): the mode chooser at the top picks
 * which of Ramblr's two service components is active, and the rows below configure the
 * individual invocation surfaces.
 *
 * The two modes exist because `flagRequestAccessibilityButton` is static-XML-only (targetSdk >
 * 29) yet decides the OS classification of the whole service:
 *
 *  - FLOATING ICON (default, [WhisperAccessibilityService], no flag): ordinary TOGGLE service.
 *    Ramblr's own ring starts dictation; the system Settings switch is independent and there is
 *    no invisible-toggle trap. This is the pre-#211 world, restored for everyone by default.
 *  - SYSTEM CONTROLS (opt-in, [SystemControlsAccessibilityService], flag declared): the OS
 *    a11y button/gesture and the volume-keys hold invoke dictation -- at the price of the
 *    INVISIBLE_TOGGLE coupling (last shortcut off kills the service), which the #220 guard-rail
 *    banner watches for while this mode is active.
 *
 * Mode switching ([InvocationServiceMode.switchMode]) flips which component is PM-enabled. With
 * the optional WRITE_SECURE_SETTINGS tier (adb grant, feature-detected) the switch is seamless:
 * `enabled_accessibility_services` is rewritten in the same motion, so the service hops
 * components without a Settings visit. Without it, the switch PM-flips the components (killing
 * the running service -- the confirmation dialog warns about exactly this) and then deep-links
 * to system Settings, where the single visible Ramblr entry needs one enable tap.
 *
 * The floating ring stays available in BOTH modes (someone on system controls may still want
 * it) but is defaulted OFF on the switch into system mode so there aren't two floating buttons;
 * the volume-keys and button rows are inert in floating-icon mode -- for a TOGGLE-class service
 * the volume-keys shortcut would toggle the SERVICE on/off (AMS semantics), not dictation, so
 * offering it there would be a trap of its own.
 */
class InvocationActivity : BaseSettingsActivity() {

    private lateinit var floatingModeCardSub: TextView
    private lateinit var systemModeCardSub: TextView
    private lateinit var ringSwitch: MaterialSwitch
    private lateinit var ringRowSub: TextView
    private lateinit var systemButtonRowSub: TextView
    private lateinit var volumeKeysRowSub: TextView
    private lateinit var advancedTierRowSub: TextView
    private lateinit var serviceOffRowSub: TextView
    private lateinit var voiceKeyboardSectionSub: TextView
    private lateinit var voiceKeyboardRowSub: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "Invocation"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        // --- Mode chooser: two exclusive cards, one per service component. ---
        root.addView(sectionHeader("Mode"))

        val floatingCard = settingsRow("Floating icon (default)", "Checking...") {
            onModeCardTapped(InvocationMode.FLOATING_ICON)
        }
        floatingModeCardSub = floatingCard.findViewWithTag("subtitle")
        root.addView(floatingCard)

        val systemCard = settingsRow("System controls", "Checking...") {
            onModeCardTapped(InvocationMode.SYSTEM_CONTROLS)
        }
        systemModeCardSub = systemCard.findViewWithTag("subtitle")
        root.addView(systemCard)

        // --- Voice keyboard (#238): deliberately its OWN section, not a third mode card.
        // The two cards above are mutually exclusive because they select which accessibility
        // *component* is PM-enabled (flagRequestAccessibilityButton is static-XML-only, so the
        // OS classification TOGGLE vs INVISIBLE_TOGGLE is fixed per component). The IME is a
        // separate OS registration in enabled_input_methods and coexists with either -- both
        // run simultaneously on a real device. Putting it in the exclusive selector would
        // encode a mutual exclusivity that does not exist.
        root.addView(sectionHeader("Voice keyboard"))
        voiceKeyboardSectionSub = TextView(this).apply {
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(24), 0, dp(24), dp(8))
            text = "Checking..."
        }
        root.addView(voiceKeyboardSectionSub)

        val voiceKeyboardRow = settingsRow("Ramblr keyboard", "Checking...") {
            onVoiceKeyboardRowTapped()
        }
        voiceKeyboardRowSub = voiceKeyboardRow.findViewWithTag("subtitle")
        root.addView(voiceKeyboardRow)

        root.addView(sectionHeader("How to start dictation"))

        // --- Floating ring (app-owned, works in BOTH modes): a direct toggle over
        // IconHiddenState, the same flag the long-press "Hide icon" menu and BehaviorActivity's
        // restore row already share.
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

        // --- System accessibility button / gesture (OS-owned; system mode only).
        val systemButtonRow = settingsRow("System accessibility button / gesture", "Checking...") {
            if (InvocationServiceMode.currentMode(this) != InvocationMode.SYSTEM_CONTROLS) {
                toast("Switch to System controls mode first")
                return@settingsRow
            }
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

        // --- Volume-keys hold (OS-owned; system mode only -- and hard-gated, not just greyed:
        // for the floating component (TOGGLE class, no button flag) the OS's volume-keys
        // shortcut TOGGLES THE SERVICE on/off (AMS.java 4296-4306) instead of invoking
        // dictation, so binding it in that mode would be a foot-gun. On the system component
        // (INVISIBLE_TOGGLE) the same shortcut fires the a11y-button callback -> dictation, and
        // can only ever enable the service, never disable it (#156 memo, AMS 4295-4331).
        val volumeKeysRow = settingsRow("Volume-keys hold", "Checking...") {
            if (InvocationServiceMode.currentMode(this) != InvocationMode.SYSTEM_CONTROLS) {
                toast("Switch to System controls mode first")
                return@settingsRow
            }
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

        // --- QS tile (app-owned, #127, unchanged by the mode). The OS never tells an app
        // whether its tile is currently placed in the panel, so this row is an add-affordance,
        // not a status readout.
        root.addView(settingsRow(
            "Quick Settings tile",
            invocationQsTileSubtitleText(canRequestAdd = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ) { onQsTileRowTapped() })

        root.addView(sectionHeader("Advanced"))

        // --- #254 off switch. Deliberately NOT hidden in Advanced-only territory despite
        // sitting under that header: in system-controls mode this is the ONLY off switch that
        // exists anywhere, because the OS hides the master toggle on Ramblr's own Accessibility
        // page for INVISIBLE_TOGGLE services. A user who wants Ramblr gone before opening a
        // banking app must be able to find it here.
        val serviceOffRow = settingsRow("Turn Ramblr off", "Checking...") { onServiceOffRowTapped() }
        serviceOffRowSub = serviceOffRow.findViewWithTag("subtitle")
        root.addView(serviceOffRow)

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
        val mode = InvocationServiceMode.currentMode(this)
        val systemMode = mode == InvocationMode.SYSTEM_CONTROLS
        val ringVisible = !IconHiddenState.isHidden(this)
        val direct = InvocationSecureSettings.canWrite(this)
        floatingModeCardSub.text = invocationFloatingModeSubtitleText(active = !systemMode)
        systemModeCardSub.text = invocationSystemModeSubtitleText(active = systemMode, directControl = direct)
        ringSwitch.isChecked = ringVisible
        ringRowSub.text = invocationRingSubtitleText(ringVisible)
        systemButtonRowSub.text = invocationSystemButtonSubtitleText(
            modeActive = systemMode,
            bound = InvocationSecureSettings.isButtonTargetBound(this),
            directControl = direct,
        )
        volumeKeysRowSub.text = invocationVolumeKeysSubtitleText(
            modeActive = systemMode,
            bound = InvocationSecureSettings.isVolumeKeysBound(this),
            directControl = direct,
        )
        advancedTierRowSub.text = invocationAdvancedTierSubtitleText(granted = direct)
        serviceOffRowSub.text = serviceOffRowSubtitleText(
            serviceEnabled = InvocationSecureSettings.isServiceEnabled(this),
            mode = mode,
        )

        // Read live from the OS every refresh -- nothing about the keyboard is persisted by
        // Ramblr, so onResume after a trip to system settings always reflects reality.
        val keyboardStatus = voiceKeyboardStatus()
        voiceKeyboardSectionSub.text = invocationVoiceKeyboardSectionSubtitleText(keyboardStatus)
        voiceKeyboardRowSub.text = invocationVoiceKeyboardSubtitleText(keyboardStatus)
    }

    // --- #238 voice keyboard --------------------------------------------------------------

    /**
     * The keyboard's live OS state. Enablement comes from the InputMethodManager's enabled list
     * and default-ness from Settings.Secure.DEFAULT_INPUT_METHOD, which is a component string
     * that must be parsed rather than string-compared -- the OS may store either flatten form.
     */
    private fun voiceKeyboardStatus(): VoiceKeyboardStatus {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager?.enabledInputMethodList?.any {
            it.packageName == packageName && it.serviceName == RamblrImeService::class.java.name
        } == true
        val default = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val isDefault = default != null && componentEquals(
            default,
            ComponentName(this, RamblrImeService::class.java).flattenToString(),
        )
        return resolveVoiceKeyboardStatus(isEnabled = enabled, isDefault = isDefault)
    }

    /**
     * Enabling an IME and choosing it as the default live on two different system screens, and
     * neither is something an app may do for the user -- so this routes to the correct one for
     * the current state rather than pretending there is one action.
     */
    private fun onVoiceKeyboardRowTapped() {
        val (title, message, positive) = when (voiceKeyboardStatus()) {
            VoiceKeyboardStatus.DISABLED -> Triple(
                "Turn on the Ramblr keyboard?",
                "Android needs you to enable it yourself.\n\n" +
                    "On the next screen, find \u201cRamblr Voice\u201d in the on-screen keyboard " +
                    "list and turn it on. Keep your usual keyboard enabled too \u2014 you can " +
                    "switch between them any time.\n\n" +
                    "The keyboard works alongside your invocation mode; turning it on does " +
                    "not change anything above.",
                "Open keyboard settings",
            )
            VoiceKeyboardStatus.ENABLED_NOT_DEFAULT -> Triple(
                "Switch to the Ramblr keyboard?",
                "The Ramblr keyboard is on, but another keyboard is still your default, " +
                    "so it won't open on its own.\n\n" +
                    "Pick \u201cRamblr Voice\u201d in the switcher to use it now. Your other " +
                    "keyboard stays enabled.",
                "Show keyboard switcher",
            )
            VoiceKeyboardStatus.DEFAULT -> Triple(
                "Ramblr keyboard is your default",
                "It opens automatically in text fields.\n\n" +
                    "To go back to another keyboard, use the keyboard switcher or Android's " +
                    "on-screen keyboard settings.",
                "Open keyboard settings",
            )
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ ->
                // showInputMethodPicker is the OS-sanctioned way to change the active IME; an
                // app cannot set DEFAULT_INPUT_METHOD itself (WRITE_SECURE_SETTINGS, and even
                // then it is the user's choice to make).
                if (voiceKeyboardStatus() == VoiceKeyboardStatus.ENABLED_NOT_DEFAULT) {
                    getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                } else {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Mode switching -------------------------------------------------------------------

    /**
     * A mode card tap: no-op when already active; otherwise confirm with tier-appropriate copy
     * (the base tier's dialog must set the expectation that dictation stops until the one
     * enable tap in system Settings), then run [InvocationServiceMode.switchMode] and follow up
     * per its result.
     */
    private fun onModeCardTapped(target: InvocationMode) {
        if (InvocationServiceMode.currentMode(this) == target) return
        val direct = InvocationSecureSettings.canWrite(this)
        val (title, message) = when (target) {
            InvocationMode.SYSTEM_CONTROLS ->
                "Switch to System controls?" to (
                    "Ramblr will switch to a service variant that supports the system " +
                        "accessibility button, gesture, and volume-keys hold.\n\n" +
                        (if (direct)
                            "The switch is automatic \u2014 dictation keeps working throughout."
                        else
                            "Dictation will STOP for a moment: Android needs you to re-enable " +
                            "Ramblr with one tap on the next screen (there will be exactly one " +
                            "Ramblr entry \u2014 just turn it on).") +
                        "\n\nThe floating ring will be hidden to avoid two on-screen buttons; " +
                        "you can turn it back on below.\n\n" +
                        "\u26a0\ufe0f In this mode, the system couples Ramblr to its shortcuts: " +
                        "turning off the LAST \u201cRamblr shortcut\u201d in system Settings also " +
                        "turns Ramblr off. If that happens, Ramblr shows a recovery banner."
                )
            InvocationMode.FLOATING_ICON ->
                "Switch to Floating icon?" to (
                    "Ramblr will switch back to the default service variant: the floating ring " +
                        "starts dictation, the system button/gesture and volume-keys shortcuts " +
                        "go away, and the system Settings switch becomes a simple independent " +
                        "on/off again.\n\n" +
                        (if (direct)
                            "The switch is automatic \u2014 dictation keeps working throughout."
                        else
                            "Dictation will STOP for a moment: Android needs you to re-enable " +
                            "Ramblr with one tap on the next screen (there will be exactly one " +
                            "Ramblr entry \u2014 just turn it on).")
                )
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Switch") { _, _ -> performModeSwitch(target) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performModeSwitch(target: InvocationMode) {
        // No-double-icon default: entering system mode hides the ring (it stays available via
        // the toggle below -- deliberate default, not a removal). Leaving system mode restores
        // it, since the ring is the only invocation surface floating-icon mode has.
        if (target == InvocationMode.SYSTEM_CONTROLS) {
            IconHiddenState.setHidden(this, true)
            WhisperAccessibilityService.instance?.applyOverlayVisibility()
        } else {
            IconHiddenState.setHidden(this, false)
            IconVisibilityNotifications.cancel(this)
            WhisperAccessibilityService.instance?.applyOverlayVisibility()
        }
        when (InvocationServiceMode.switchMode(this, target)) {
            InvocationServiceMode.SwitchResult.SEAMLESS -> {
                toast("Mode switched")
                refresh()
            }
            InvocationServiceMode.SwitchResult.NEEDS_SETTINGS_TAP -> {
                // The one guided tap: the old component is already PM-disabled, so system
                // Settings shows exactly one Ramblr entry -- the target component -- and its
                // details page has the enable switch front and center.
                openServiceDetailsSettings()
            }
            InvocationServiceMode.SwitchResult.NO_OP -> refresh()
        }
    }

    // --- OS shortcut rows (system mode only) ----------------------------------------------

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
     * nothing on that page says so. (Only reachable in system-controls mode -- the floating
     * component isn't subject to the trap, but its rows never lead here.)
     */
    private fun showOsShortcutExplainerThenDeepLink(methodName: String, howTo: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Before you go: how this switch behaves")
            .setMessage(
                "The $methodName is managed on Ramblr's system Accessibility page.\n\n$howTo\n\n" +
                    "\u26a0\ufe0f Important: the \u201cRamblr shortcut\u201d switch controls where the " +
                    "shortcut appears \u2014 but turning off the LAST shortcut also turns off " +
                    "Ramblr itself (system behavior for always-on accessibility tools). To keep " +
                    "dictation available without any system shortcut, switch back to Floating " +
                    "icon mode on this screen instead."
            )
            .setPositiveButton("Open system settings") { _, _ -> openServiceDetailsSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Deep-link to the ACTIVE component's own service page (action string public-in-behavior
     *  since API 30 = minSdk; see [InvocationSecureSettings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS]
     *  for why it's a spelled-out string). Falls back to the top-level Accessibility list if an
     *  OEM skin doesn't resolve the details action -- which is also fine for the mode-switch
     *  flow, since only one Ramblr entry is visible there. */
    private fun openServiceDetailsSettings() {
        val component = InvocationServiceMode.activeComponent(this)
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

    // --- #254 in-app off switch -------------------------------------------------------------

    /**
     * "Turn Ramblr off": the app-side master off switch, and in system-controls mode the only
     * one that exists (the OS hides the master toggle on an INVISIBLE_TOGGLE service's own
     * Accessibility page). Confirm first -- this stops dictation everywhere -- then take the
     * action [resolveServiceOffAction] picks, sweeping OS shortcut bindings beforehand when we
     * can, since a bound volume-keys shortcut would otherwise turn the service straight back on
     * (AMS.performAccessibilityShortcutTargetService).
     */
    private fun onServiceOffRowTapped() {
        val action = resolveServiceOffAction(
            serviceEnabled = InvocationSecureSettings.isServiceEnabled(this),
            serviceConnected = WhisperAccessibilityService.instance != null,
        )
        if (action == ServiceOffAction.ALREADY_OFF) {
            toast("Ramblr's accessibility service is already off")
            refresh()
            return
        }
        val risk = resolveServiceOffShortcutRisk(
            mode = InvocationServiceMode.currentMode(this),
            buttonTargetBound = InvocationSecureSettings.isButtonTargetBound(this),
            volumeKeysBound = InvocationSecureSettings.isVolumeKeysBound(this),
            canWrite = InvocationSecureSettings.canWrite(this),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Turn Ramblr off?")
            .setMessage(serviceOffConfirmMessage(risk))
            .setPositiveButton("Turn off") { _, _ -> performServiceOff(action, risk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performServiceOff(action: ServiceOffAction, risk: ServiceOffShortcutRisk) {
        // Sweep the OS shortcut bindings BEFORE disabling, in that order and only on the tier
        // that can: raw Secure writes bypass the invisible-toggle sync (#156 memo §3), so this
        // cannot itself trip the "last shortcut off" kill, and once nothing is bound there is
        // nothing left that can resurrect the service after disableSelf().
        if (risk == ServiceOffShortcutRisk.SWEEPABLE) {
            InvocationSecureSettings.setBinding(
                this, InvocationSecureSettings.KEY_BUTTON_TARGETS, bound = false,
            )
            InvocationSecureSettings.setBinding(
                this, InvocationSecureSettings.KEY_SHORTCUT_TARGET_SERVICE, bound = false,
            )
        }
        // The guard-rail banner must not fire for a disable the user just asked for: dismissing
        // it here marks this detection handled, and the service clears the dismissal itself the
        // next time it connects, so a genuine future kill still gets the banner.
        InvocationGuardRail.dismissBanner(this)
        if (action == ServiceOffAction.SELF_DISABLE && WhisperAccessibilityService.disableServiceFromApp()) {
            toast("Ramblr turned off")
            // The OS unbinds asynchronously, so an immediate re-read can still show the old
            // enabled state; refresh on the next resume instead of asserting a state we
            // haven't observed.
            return
        }
        // Listed as enabled with no live instance to disable (crashed / not yet connected):
        // only system Settings can clear the entry. Same deep link the mode switch uses.
        toast("Opening Ramblr's accessibility settings")
        openServiceDetailsSettings()
    }

    /** The advanced tier's explainer: what it unlocks, the exact adb command (selectable for
     *  copy), and the live granted/not-granted state. */
    private fun showAdvancedTierDialog() {
        val granted = InvocationSecureSettings.canWrite(this)
        val command = wssAdbCommand(packageName)
        val message = TextView(this).apply {
            text = (if (granted) "\u2705 Active. Mode switches happen seamlessly (no system " +
                "Settings visit, dictation keeps working), shortcut switches apply instantly " +
                "in-app, and none of it can trip the system's \u201clast shortcut off turns " +
                "the service off\u201d behavior.\n\n"
            else "Android only lets apps change system shortcut bindings with a permission that " +
                "must be granted once over adb (it survives reboots, but not reinstalls):\n\n") +
                "$command\n\n" +
                "With it granted, mode switches complete in-app without the system Settings " +
                "round-trip, the shortcut rows switch instantly, and if the service ever gets " +
                "turned off by the system switch, Ramblr can turn itself back on with one tap."
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
            mode = InvocationServiceMode.currentMode(context),
            ringVisible = !IconHiddenState.isHidden(context),
            systemButtonBound = InvocationSecureSettings.isButtonTargetBound(context),
            volumeKeysBound = InvocationSecureSettings.isVolumeKeysBound(context),
        )
    }
}
