package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #156 invocation-chooser + guard-rail logic, pinned at the pure layer
 * ([InvocationMethods.kt]) because none of it can be exercised on the JVM through the Activity /
 * ContentResolver code that calls it -- the same reasoning as [SettingsSubtitlesTest].
 *
 * The component-list helpers get the heaviest coverage: with WRITE_SECURE_SETTINGS granted they
 * read-modify-write the OS's real `Settings.Secure` accessibility lists, where corrupting
 * ANOTHER app's entry (e.g. Tasker's) would break that app's accessibility setup with nothing
 * pointing back at Ramblr as the cause.
 */
class InvocationMethodsTest {

    private val ramblr = "com.trevornk.ramblr/com.trevornk.ramblr.WhisperAccessibilityService"
    private val ramblrShort = "com.trevornk.ramblr/.WhisperAccessibilityService"
    private val ramblrSystem = "com.trevornk.ramblr/com.trevornk.ramblr.SystemControlsAccessibilityService"
    private val ramblrSystemShort = "com.trevornk.ramblr/.SystemControlsAccessibilityService"
    private val tasker = "net.dinglisch.android.taskerm/net.dinglisch.android.taskerm.MyAccessibilityService"
    private val other = "com.example.other/.OtherService"

    // --- normalizeComponent / componentEquals -----------------------------------------------

    @Test fun `short flatten form expands to full form`() {
        assertEquals(ramblr, normalizeComponent(ramblrShort))
    }

    @Test fun `full flatten form is unchanged`() {
        assertEquals(ramblr, normalizeComponent(ramblr))
    }

    @Test fun `non component-shaped strings pass through unchanged`() {
        // The helpers must tolerate junk in the settings value without corrupting it.
        assertEquals("garbage", normalizeComponent("garbage"))
        assertEquals("/leading", normalizeComponent("/leading"))
        assertEquals("trailing/", normalizeComponent("trailing/"))
        assertEquals("", normalizeComponent(""))
    }

    @Test fun `componentEquals matches across flatten forms in both directions`() {
        assertTrue(componentEquals(ramblr, ramblrShort))
        assertTrue(componentEquals(ramblrShort, ramblr))
        assertFalse(componentEquals(ramblr, tasker))
    }

    // --- componentListEntries / componentListContains ---------------------------------------

    @Test fun `null and empty lists have no entries`() {
        assertEquals(emptyList<String>(), componentListEntries(null))
        assertEquals(emptyList<String>(), componentListEntries(""))
        assertFalse(componentListContains(null, ramblr))
        assertFalse(componentListContains("", ramblr))
    }

    @Test fun `doubled and stray separators are dropped, not turned into empty entries`() {
        assertEquals(listOf(tasker, ramblr), componentListEntries("$tasker::$ramblr:"))
    }

    @Test fun `contains matches the short form against a full-form list and vice versa`() {
        assertTrue(componentListContains(ramblr, ramblrShort))
        assertTrue(componentListContains(ramblrShort, ramblr))
        assertTrue(componentListContains("$tasker:$ramblrShort", ramblr))
        assertFalse(componentListContains(tasker, ramblr))
    }

    // --- componentListAdd -------------------------------------------------------------------

    @Test fun `add to empty or null list yields just the component`() {
        assertEquals(ramblr, componentListAdd(null, ramblr))
        assertEquals(ramblr, componentListAdd("", ramblr))
    }

    @Test fun `add preserves other apps' entries verbatim and appends last`() {
        assertEquals("$tasker:$other:$ramblr", componentListAdd("$tasker:$other", ramblr))
    }

    @Test fun `add is a no-op when already present`() {
        assertEquals("$tasker:$ramblr", componentListAdd("$tasker:$ramblr", ramblr))
    }

    @Test fun `add is a no-op when present in the other flatten form`() {
        // The OS may have written the short form; adding the full form must not duplicate it.
        assertEquals("$tasker:$ramblrShort", componentListAdd("$tasker:$ramblrShort", ramblr))
    }

    // --- componentListRemove ----------------------------------------------------------------

    @Test fun `remove from null or empty list yields empty string`() {
        assertEquals("", componentListRemove(null, ramblr))
        assertEquals("", componentListRemove("", ramblr))
    }

    @Test fun `removing the only entry yields empty string`() {
        // The OS persists "" (not null) for an emptied target list; "" is what gets written back.
        assertEquals("", componentListRemove(ramblr, ramblr))
    }

    @Test fun `remove preserves other apps' entries verbatim, in order`() {
        assertEquals("$tasker:$other", componentListRemove("$tasker:$ramblr:$other", ramblr))
    }

    @Test fun `remove matches the other flatten form`() {
        assertEquals(tasker, componentListRemove("$tasker:$ramblrShort", ramblr))
        assertEquals(tasker, componentListRemove("$tasker:$ramblr", ramblrShort))
    }

    @Test fun `remove drops duplicates of the same component in mixed forms`() {
        assertEquals(tasker, componentListRemove("$ramblr:$tasker:$ramblrShort", ramblr))
    }

    @Test fun `remove of an absent component leaves the list unchanged`() {
        assertEquals("$tasker:$other", componentListRemove("$tasker:$other", ramblr))
    }

    @Test fun `add then remove round-trips back to the original entries`() {
        val original = "$tasker:$other"
        assertEquals(original, componentListRemove(componentListAdd(original, ramblr), ramblr))
    }

    // --- componentListReplace (#156 dual-component mode switch) -----------------------------

    @Test fun `replace swaps old for new in place, preserving other apps' entries verbatim`() {
        // The exact seamless-switch scenario from the memo baseline: Tasker + Ramblr enabled.
        assertEquals(
            "$tasker:$ramblrSystem",
            componentListReplace("$tasker:$ramblr", ramblr, ramblrSystem),
        )
    }

    @Test fun `replace works in the other direction too`() {
        assertEquals(
            "$tasker:$ramblr",
            componentListReplace("$tasker:$ramblrSystem", ramblrSystem, ramblr),
        )
    }

    @Test fun `replace keeps position - old entry mid-list stays mid-list`() {
        assertEquals(
            "$tasker:$ramblrSystem:$other",
            componentListReplace("$tasker:$ramblr:$other", ramblr, ramblrSystem),
        )
    }

    @Test fun `replace matches the old component in short flatten form`() {
        assertEquals(
            "$tasker:$ramblrSystem",
            componentListReplace("$tasker:$ramblrShort", ramblr, ramblrSystem),
        )
        assertEquals(
            "$tasker:$ramblrSystem",
            componentListReplace("$tasker:$ramblr", ramblrShort, ramblrSystem),
        )
    }

    @Test fun `replace with old absent appends new last and keeps everything else`() {
        // The old component had already been disabled out from under us -- the switch must
        // still land the user in a working state.
        assertEquals(
            "$tasker:$other:$ramblrSystem",
            componentListReplace("$tasker:$other", ramblr, ramblrSystem),
        )
        assertEquals(ramblrSystem, componentListReplace(null, ramblr, ramblrSystem))
        assertEquals(ramblrSystem, componentListReplace("", ramblr, ramblrSystem))
    }

    @Test fun `replace with both present dedupes to a single new entry`() {
        // A crashed previous switch can leave both components listed; the retry must converge
        // to exactly one.
        assertEquals(
            "$tasker:$ramblrSystem",
            componentListReplace("$tasker:$ramblr:$ramblrSystem", ramblr, ramblrSystem),
        )
        // ...matching the new component's short form too.
        assertEquals(
            "$tasker:$ramblrSystemShort",
            componentListReplace("$tasker:$ramblrSystemShort:$ramblr", ramblr, ramblrSystem),
        )
    }

    @Test fun `replace drops duplicate old entries in mixed flatten forms`() {
        assertEquals(
            "$ramblrSystem:$tasker",
            componentListReplace("$ramblr:$tasker:$ramblrShort", ramblr, ramblrSystem),
        )
    }

    @Test fun `replace when already switched is idempotent`() {
        assertEquals(
            "$tasker:$ramblrSystem",
            componentListReplace("$tasker:$ramblrSystem", ramblr, ramblrSystem),
        )
    }

    @Test fun `replace round-trips back to the original list`() {
        val original = "$tasker:$ramblr:$other"
        assertEquals(
            original,
            componentListReplace(componentListReplace(original, ramblr, ramblrSystem), ramblrSystem, ramblr),
        )
    }

    // --- resolveInvocationMode --------------------------------------------------------------

    @Test fun `system component PM-enabled resolves to system controls mode`() {
        assertEquals(InvocationMode.SYSTEM_CONTROLS, resolveInvocationMode(systemComponentPmEnabled = true))
    }

    @Test fun `system component PM-disabled resolves to floating icon mode`() {
        // The floating component's own PM state is deliberately not an input: mid-switch both
        // components can briefly be enabled, and "system on" must win for retries to converge.
        assertEquals(InvocationMode.FLOATING_ICON, resolveInvocationMode(systemComponentPmEnabled = false))
    }

    // --- shouldShowServiceKilledBanner ------------------------------------------------------

    @Test fun `banner fires on the exact invisible-toggle kill state in system controls mode`() {
        assertTrue(shouldShowServiceKilledBanner(
            systemControlsModeActive = true,
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `banner never fires in floating icon mode - that component has no trap`() {
        // The floating component is TOGGLE-classified: a disabled service there is a user
        // choice made on an independent Settings switch, not the invisible-toggle kill.
        assertFalse(shouldShowServiceKilledBanner(
            systemControlsModeActive = false,
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `fresh install that never enabled the service shows nothing`() {
        assertFalse(shouldShowServiceKilledBanner(
            systemControlsModeActive = true,
            serviceWasEnabled = false,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `service currently enabled shows nothing`() {
        assertFalse(shouldShowServiceKilledBanner(
            systemControlsModeActive = true,
            serviceWasEnabled = true,
            serviceEnabledNow = true,
            anyShortcutBound = false,
            bannerDismissed = false,
        ))
    }

    @Test fun `service off but a shortcut still bound is not the invisible-toggle kill`() {
        // A remaining binding means the OS did NOT run the "last shortcut removed" sync -- the
        // service is off for some other reason (and the bound shortcut can self-heal it: the
        // volume-keys path re-enables a button-flag service on use). Don't misdiagnose.
        assertFalse(shouldShowServiceKilledBanner(
            systemControlsModeActive = true,
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = true,
            bannerDismissed = false,
        ))
    }

    @Test fun `dismissed banner stays quiet until re-armed`() {
        assertFalse(shouldShowServiceKilledBanner(
            systemControlsModeActive = true,
            serviceWasEnabled = true,
            serviceEnabledNow = false,
            anyShortcutBound = false,
            bannerDismissed = true,
        ))
    }

    // --- subtitle formatters ----------------------------------------------------------------

    @Test fun `main row subtitle leads with the mode and lists active methods`() {
        assertEquals(
            "System controls mode — ring on, volume keys on",
            invocationMainRowSubtitleText(
                mode = InvocationMode.SYSTEM_CONTROLS,
                ringVisible = true, systemButtonBound = false, volumeKeysBound = true,
            ),
        )
    }

    @Test fun `main row subtitle in floating mode ignores stale system bindings`() {
        // Leftover accessibility_button_targets entries from a previous system-mode stint must
        // not be reported as active methods while the floating component is the live one.
        assertEquals(
            "Floating icon mode — ring on",
            invocationMainRowSubtitleText(
                mode = InvocationMode.FLOATING_ICON,
                ringVisible = true, systemButtonBound = true, volumeKeysBound = true,
            ),
        )
    }

    @Test fun `main row subtitle with nothing active says so`() {
        assertEquals(
            "Floating icon mode — no method currently active",
            invocationMainRowSubtitleText(
                mode = InvocationMode.FLOATING_ICON,
                ringVisible = false, systemButtonBound = false, volumeKeysBound = false,
            ),
        )
    }

    @Test fun `floating mode card subtitle reflects active state`() {
        assertTrue(invocationFloatingModeSubtitleText(active = true).startsWith("Active"))
        assertFalse(invocationFloatingModeSubtitleText(active = false).startsWith("Active"))
    }

    @Test fun `system mode card subtitle names the switch cost per tier when inactive`() {
        assertTrue(invocationSystemModeSubtitleText(active = true, directControl = false).startsWith("Active"))
        assertTrue(
            invocationSystemModeSubtitleText(active = false, directControl = true)
                .contains("switches instantly"),
        )
        assertTrue(
            invocationSystemModeSubtitleText(active = false, directControl = false)
                .contains("one enable tap in system Settings"),
        )
    }

    @Test fun `ring subtitle reflects visibility`() {
        assertEquals("On — tap the ring to start and stop dictation", invocationRingSubtitleText(visible = true))
        assertEquals("Off — the ring is hidden", invocationRingSubtitleText(visible = false))
    }

    @Test fun `system button subtitle distinguishes deep-link and direct-control tiers`() {
        assertEquals(
            "On — the system button/gesture starts dictation · tap to open system settings",
            invocationSystemButtonSubtitleText(modeActive = true, bound = true, directControl = false),
        )
        assertEquals(
            "Off · tap to switch in-app",
            invocationSystemButtonSubtitleText(modeActive = true, bound = false, directControl = true),
        )
    }

    @Test fun `system button subtitle in floating mode says the mode is required`() {
        assertEquals(
            "Requires System controls mode",
            invocationSystemButtonSubtitleText(modeActive = false, bound = true, directControl = true),
        )
    }

    @Test fun `volume keys subtitle distinguishes deep-link and direct-control tiers`() {
        assertEquals(
            "On — hold both volume keys to start dictation · tap to switch in-app",
            invocationVolumeKeysSubtitleText(modeActive = true, bound = true, directControl = true),
        )
        assertEquals(
            "Off · tap to open system settings",
            invocationVolumeKeysSubtitleText(modeActive = true, bound = false, directControl = false),
        )
    }

    @Test fun `volume keys subtitle in floating mode warns about the toggle semantics`() {
        // In floating-icon (TOGGLE-class) mode the OS volume-keys shortcut toggles the SERVICE
        // on/off rather than invoking dictation -- the copy must say so, not just grey out.
        val text = invocationVolumeKeysSubtitleText(modeActive = false, bound = false, directControl = true)
        assertTrue(text.contains("Requires System controls mode"))
        assertTrue(text.contains("turn Ramblr itself off and on"))
    }

    @Test fun `qs tile subtitle offers one-tap add only where the API exists`() {
        assertTrue(invocationQsTileSubtitleText(canRequestAdd = true).contains("tap to add the tile"))
        assertTrue(invocationQsTileSubtitleText(canRequestAdd = false).contains("tap for setup steps"))
    }

    // --- #238 voice-keyboard status ---------------------------------------------------------

    @Test fun `voice keyboard status distinguishes disabled, enabled, and default`() {
        assertEquals(
            VoiceKeyboardStatus.DISABLED,
            resolveVoiceKeyboardStatus(isEnabled = false, isDefault = false),
        )
        assertEquals(
            VoiceKeyboardStatus.ENABLED_NOT_DEFAULT,
            resolveVoiceKeyboardStatus(isEnabled = true, isDefault = false),
        )
        assertEquals(
            VoiceKeyboardStatus.DEFAULT,
            resolveVoiceKeyboardStatus(isEnabled = true, isDefault = true),
        )
    }

    /**
     * A device claiming "default" while the IME is not enabled is incoherent -- the OS would not
     * show it. Enablement wins so the UI never tells the user a keyboard is in use that the
     * system will not present.
     */
    @Test fun `voice keyboard status refuses to report default when not enabled`() {
        assertEquals(
            VoiceKeyboardStatus.DISABLED,
            resolveVoiceKeyboardStatus(isEnabled = false, isDefault = true),
        )
    }

    @Test fun `voice keyboard subtitle names the next action for each state`() {
        assertTrue(
            invocationVoiceKeyboardSubtitleText(VoiceKeyboardStatus.DISABLED)
                .contains("tap to turn the Ramblr keyboard on"),
        )
        // The enabled-not-default case is the one users cannot otherwise diagnose: the keyboard
        // is on but nothing appears to happen, because another IME is still the default.
        val enabled = invocationVoiceKeyboardSubtitleText(VoiceKeyboardStatus.ENABLED_NOT_DEFAULT)
        assertTrue(enabled.contains("not your default keyboard"))
        assertTrue(enabled.contains("keyboard switcher"))
        assertTrue(
            invocationVoiceKeyboardSubtitleText(VoiceKeyboardStatus.DEFAULT)
                .contains("opens automatically"),
        )
    }

    /**
     * The section sits under two mutually exclusive mode cards, so its subtitle must state the
     * coexistence in every state or it reads as a third option in that set.
     */
    @Test fun `voice keyboard section subtitle always states independence from the mode cards`() {
        VoiceKeyboardStatus.entries.forEach { status ->
            val text = invocationVoiceKeyboardSectionSubtitleText(status)
            assertTrue(
                "status $status must not read as a third mode option",
                text.contains("Independent of the mode above") || text.contains("Works alongside the mode above"),
            )
        }
    }

    /**
     * DEFAULT_INPUT_METHOD is a component string whose flatten form is the OS's choice, and a
     * real device stores the SHORT form: a Pixel 10a reports
     * "com.trevornk.ramblr/.RamblrImeService" while ComponentName.flattenToString() produces the
     * long form. A plain string compare would report "not default" on the very device where the
     * keyboard IS the default, so the comparison must normalize -- this pins the real value.
     */
    @Test fun `default ime comparison matches the short flatten form a real device stores`() {
        val stored = "com.trevornk.ramblr/.RamblrImeService"
        val flattened = "com.trevornk.ramblr/com.trevornk.ramblr.RamblrImeService"
        assertTrue(componentEquals(stored, flattened))
        assertEquals(
            VoiceKeyboardStatus.DEFAULT,
            resolveVoiceKeyboardStatus(isEnabled = true, isDefault = componentEquals(stored, flattened)),
        )
        // A different IME being default must not read as ours.
        assertFalse(componentEquals(
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
            flattened,
        ))
    }

    @Test fun `advanced tier subtitle reflects the feature-detected grant`() {
        assertTrue(invocationAdvancedTierSubtitleText(granted = true).startsWith("Active"))
        assertTrue(invocationAdvancedTierSubtitleText(granted = false).contains("adb"))
    }

    @Test fun `adb command names the exact package and permission`() {
        assertEquals(
            "adb shell pm grant com.trevornk.ramblr android.permission.WRITE_SECURE_SETTINGS",
            wssAdbCommand("com.trevornk.ramblr"),
        )
    }

    // --- seamlessSwitchWrites ordering invariant (#156 race fix, device-verified) ---

    @Test
    fun seamlessWrites_enteringSystemMode_bindsButtonTargetBeforeSwap() {
        val writes = seamlessSwitchWrites(InvocationMode.SYSTEM_CONTROLS)
        assertEquals(
            listOf(SeamlessWrite.BIND_BUTTON_TARGET, SeamlessWrite.SWAP_ENABLED_SERVICES),
            writes,
        )
    }

    @Test
    fun seamlessWrites_leavingSystemMode_swapsBeforeUnbindingShortcuts() {
        val writes = seamlessSwitchWrites(InvocationMode.FLOATING_ICON)
        assertEquals(
            listOf(SeamlessWrite.SWAP_ENABLED_SERVICES, SeamlessWrite.UNBIND_ALL_SHORTCUTS),
            writes,
        )
    }

    @Test
    fun seamlessWrites_enteringSystemMode_neverUnbindsShortcuts() {
        assertFalse(
            seamlessSwitchWrites(InvocationMode.SYSTEM_CONTROLS)
                .contains(SeamlessWrite.UNBIND_ALL_SHORTCUTS),
        )
    }

    @Test
    fun seamlessWrites_leavingSystemMode_neverBindsButtonTarget() {
        assertFalse(
            seamlessSwitchWrites(InvocationMode.FLOATING_ICON)
                .contains(SeamlessWrite.BIND_BUTTON_TARGET),
        )
    }
}
