package com.trevornk.ramblr

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The Android side of the #156 dual-component design: which of the two service components is
 * the active one, and the mode switch that flips them.
 *
 * Both components run the identical service code ([SystemControlsAccessibilityService] is an
 * empty subclass of [WhisperAccessibilityService]); they differ only in their static meta-data
 * XML -- the system component declares `flagRequestAccessibilityButton`, the floating one
 * doesn't. The flag can't be toggled at runtime (ignored for targetSdk > 29) and it decides the
 * OS classification (INVISIBLE_TOGGLE vs TOGGLE), so "which world are we in" is exactly "which
 * component is PM-enabled", flipped via [PackageManager.setComponentEnabledSetting].
 *
 * The mode decision itself ([resolveInvocationMode]) is pure and unit-tested; this object is
 * the PM/ContentResolver plumbing plus the two switching flows:
 *
 *  - [switchMode] with WRITE_SECURE_SETTINGS granted: fully seamless. Enable target component,
 *    rewrite `enabled_accessibility_services` (swap our old component string for the new one,
 *    preserving every other app's entries -- Tasker!), disable old component. The service
 *    reconnects as the other component within a beat, no Settings visit.
 *  - [switchMode] without it: enable target, disable old (the running service dies here -- the
 *    UI warns first), then the caller deep-links to system Settings where the ONE visible
 *    Ramblr entry needs a single enable tap. One guided tap, by design: because the old
 *    component is already PM-disabled, Settings lists only the target component, so there is
 *    nothing to pick wrong.
 */
object InvocationServiceMode {

    private const val TAG = "PhoneWhisper"

    /** The default "Floating icon" component -- the pre-#211-behaving, no-button-flag service
     *  every existing install's `enabled_accessibility_services` entry already points at. */
    fun floatingComponent(context: Context): ComponentName =
        ComponentName(context, WhisperAccessibilityService::class.java)

    /** The opt-in "System controls" component (button flag declared, ships PM-disabled). */
    fun systemComponent(context: Context): ComponentName =
        ComponentName(context, SystemControlsAccessibilityService::class.java)

    /** Both Ramblr service components, full flatten form -- for "is EITHER of ours in this
     *  Settings.Secure list" checks ([InvocationSecureSettings]). */
    fun allComponents(context: Context): List<String> = listOf(
        floatingComponent(context).flattenToString(),
        systemComponent(context).flattenToString(),
    )

    /**
     * Whether [component] is PM-enabled, resolving DEFAULT against the manifest: the floating
     * component declares no android:enabled (default true), the system one ships
     * android:enabled="false".
     */
    private fun isComponentPmEnabled(context: Context, component: ComponentName, manifestDefault: Boolean): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> manifestDefault // COMPONENT_ENABLED_STATE_DEFAULT
        }

    /** The current mode, from the one deciding bit (see [resolveInvocationMode]'s kdoc for why
     *  only the system component's PM state is consulted). */
    fun currentMode(context: Context): InvocationMode = resolveInvocationMode(
        systemComponentPmEnabled = isComponentPmEnabled(context, systemComponent(context), manifestDefault = false),
    )

    /** The component that is (or should be) the live service under the current mode -- what
     *  deep links, Settings.Secure writes, and the guard rail must all target. */
    fun activeComponent(context: Context): ComponentName = when (currentMode(context)) {
        InvocationMode.FLOATING_ICON -> floatingComponent(context)
        InvocationMode.SYSTEM_CONTROLS -> systemComponent(context)
    }

    /** How a [switchMode] call completed, so the UI knows what happens next. */
    enum class SwitchResult {
        /** Already in the requested mode; nothing changed. */
        NO_OP,

        /** WRITE_SECURE_SETTINGS path: components flipped AND `enabled_accessibility_services`
         *  rewritten -- the service reconnects as the new component on its own. */
        SEAMLESS,

        /** Base-tier path: components flipped, but the OS list still needs the user to flip the
         *  single visible Ramblr entry on -- caller deep-links to system Settings now. */
        NEEDS_SETTINGS_TAP,
    }

    /**
     * Switches to [target] mode. Ordering is deliberate and identical in both tiers:
     *
     *  1. PM-ENABLE the target component first. From this moment the target is resolvable, so
     *     an `enabled_accessibility_services` entry naming it (step 2, or the user's Settings
     *     tap) can actually bind. Enabling before touching anything else also means a crash
     *     mid-switch leaves the app in the both-enabled state, which [resolveInvocationMode]
     *     deliberately reads as the TARGET mode (system wins) so a retry converges.
     *  2. Seamless tier only: read-modify-write `enabled_accessibility_services`, swapping our
     *     old component string for the new one via [componentListReplace] -- other apps'
     *     entries (Tasker's!) preserved verbatim, both flatten forms matched, deduped if the
     *     new component was somehow already listed. Raw Secure writes bypass the
     *     invisible-toggle sync (device-verified, #156 memo §3), so nothing here can trip the
     *     OS's kill logic. Doing the write BEFORE step 3 means the binding for the new
     *     component exists by the time the old one disappears -- AMS re-evaluates on both
     *     changes, so the service hop is one unbind/bind, not an unbound gap.
     *  3. PM-DISABLE the old component. If it was the live service, the OS unbinds/kills it
     *     here -- inherent to disabling a running service's component. On the seamless tier the
     *     new component is already enabled+listed, so AMS immediately binds the replacement; on
     *     the base tier the service STAYS DOWN until the user's one enable tap in Settings
     *     (the pre-switch dialog sets exactly this expectation). DONT_KILL_APP so the rest of
     *     the app process (this Activity!) survives the flip; it does not keep the old service
     *     component alive, which is fine -- it's the component being retired.
     *
     * The seamless write is wrapped so a SecurityException (grant revoked between the
     * feature-detect and the write) degrades to the base-tier result instead of crashing.
     */
    fun switchMode(context: Context, target: InvocationMode): SwitchResult {
        val current = currentMode(context)
        if (current == target) return SwitchResult.NO_OP

        val pm = context.packageManager
        val (oldComponent, newComponent) = when (target) {
            InvocationMode.SYSTEM_CONTROLS -> floatingComponent(context) to systemComponent(context)
            InvocationMode.FLOATING_ICON -> systemComponent(context) to floatingComponent(context)
        }

        // Step 1: target becomes resolvable.
        pm.setComponentEnabledSetting(
            newComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )

        // Step 2 (seamless tier): swap our entry in enabled_accessibility_services.
        val seamless = swapEnabledServicesEntry(context, oldComponent, newComponent)

        // Step 3: retire the old component (kills it if it was the live service -- inherent).
        pm.setComponentEnabledSetting(
            oldComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

        return if (seamless) SwitchResult.SEAMLESS else SwitchResult.NEEDS_SETTINGS_TAP
    }

    /**
     * The seamless tier's `enabled_accessibility_services` rewrite. Returns false (base-tier
     * fallback) when WRITE_SECURE_SETTINGS isn't granted or the write throws.
     */
    private fun swapEnabledServicesEntry(context: Context, old: ComponentName, new: ComponentName): Boolean {
        if (!InvocationSecureSettings.canWrite(context)) return false
        val key = InvocationSecureSettings.KEY_ENABLED_SERVICES
        val current = Settings.Secure.getString(context.contentResolver, key)
        val updated = componentListReplace(current, old.flattenToString(), new.flattenToString())
        return try {
            Settings.Secure.putString(context.contentResolver, key, updated)
        } catch (e: SecurityException) {
            // Feature-detect said granted but the write still failed (revoked mid-flight, OEM
            // quirk): the component flip still happens; the caller falls back to the guided tap.
            Log.e(TAG, "WRITE_SECURE_SETTINGS swap of $key failed despite grant", e)
            false
        }
    }
}
