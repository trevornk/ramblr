package com.trevornk.ramblr

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The Android side of the invocation chooser's OS-shortcut state: reads the three
 * `Settings.Secure` accessibility component lists (readable by any app, no permission) and --
 * only when the optional WRITE_SECURE_SETTINGS tier is granted via adb (#156) -- writes them.
 *
 * All list editing is delegated to the pure helpers in [InvocationMethods] so the
 * preserve-other-apps'-entries invariant is unit-tested; this object only does the
 * ContentResolver I/O. Every write path is wrapped so a SecurityException (grant revoked between
 * the feature-detect and the write -- e.g. by an app update reinstall) degrades to `false` and
 * the caller falls back to the deep-link flow instead of crashing.
 *
 * Why raw Secure writes work at all: the invisible-toggle "last shortcut off kills the service"
 * sync runs only inside AccessibilityManagerService.enableShortcutsForTargets(); AMS's
 * ContentObserver on these keys just re-reads the values. Raw writes therefore change shortcut
 * bindings WITHOUT touching the service's enabled state (device-verified in the #156 memo, §3),
 * which is exactly the decoupling the OS UI can't offer.
 */
object InvocationSecureSettings {

    private const val TAG = "PhoneWhisper"

    /** `Settings.Secure` key: nav-bar/floating a11y button (and, in button-mode 2, gesture) targets. */
    const val KEY_BUTTON_TARGETS = "accessibility_button_targets"

    /** `Settings.Secure` key: volume-keys-hold (hardware) shortcut target list. Named "_service"
     *  (singular) for legacy reasons but colon-separated like the others on current Android. */
    const val KEY_SHORTCUT_TARGET_SERVICE = "accessibility_shortcut_target_service"

    /** `Settings.Secure` key: the master enabled-services list -- the one the invisible-toggle
     *  sync empties of Ramblr when the last shortcut is removed. */
    const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"

    /**
     * The system Settings deep-link action for one service's own Accessibility page. This is
     * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`, spelled out because the SDK constant is
     * `@hide` (not in the public android.jar, so it doesn't compile) even though the ACTION
     * STRING has resolved via the Settings app's exported intent-filter since API 30 (= minSdk).
     * Callers pair it with the public [android.content.Intent.EXTRA_COMPONENT_NAME] (the extra
     * AOSP's AccessibilityDetailsSettingsActivity reads) and keep an
     * ActivityNotFoundException fallback to ACTION_ACCESSIBILITY_SETTINGS for OEM skins that
     * don't export it.
     */
    const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** Ramblr's service component in the full flatten form the OS itself writes. */
    fun serviceComponent(context: Context): String =
        ComponentName(context, WhisperAccessibilityService::class.java).flattenToString()

    /** Whether the optional advanced tier is active: `pm grant`-ed WRITE_SECURE_SETTINGS. */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // --- Reads (no permission needed) ---

    fun isButtonTargetBound(context: Context): Boolean =
        componentListContains(read(context, KEY_BUTTON_TARGETS), serviceComponent(context))

    fun isVolumeKeysBound(context: Context): Boolean =
        componentListContains(read(context, KEY_SHORTCUT_TARGET_SERVICE), serviceComponent(context))

    fun isServiceEnabled(context: Context): Boolean =
        componentListContains(read(context, KEY_ENABLED_SERVICES), serviceComponent(context))

    /** Whether ANY OS shortcut still binds Ramblr -- the guard rail's "targets empty" input. */
    fun anyShortcutBound(context: Context): Boolean =
        isButtonTargetBound(context) || isVolumeKeysBound(context)

    private fun read(context: Context, key: String): String? =
        Settings.Secure.getString(context.contentResolver, key)

    // --- Writes (advanced tier only; all return success and never throw) ---

    /** Adds/removes Ramblr in [key]'s component list, preserving every other app's entries
     *  verbatim (read-modify-write through the tested pure helpers). Returns false -- caller
     *  falls back to the deep-link flow -- if the permission is missing or the write throws. */
    fun setBinding(context: Context, key: String, bound: Boolean): Boolean {
        if (!canWrite(context)) return false
        val component = serviceComponent(context)
        val current = read(context, key)
        val updated = if (bound) componentListAdd(current, component) else componentListRemove(current, component)
        return try {
            Settings.Secure.putString(context.contentResolver, key, updated)
        } catch (e: SecurityException) {
            // Feature-detect said granted but the write still failed (revoked mid-flight, OEM
            // quirk): degrade to the deep-link path, never crash a Settings tap.
            Log.e(TAG, "WRITE_SECURE_SETTINGS write to $key failed despite grant", e)
            false
        }
    }

    /**
     * The advanced tier's true one-tap re-enable (#156 guard rail): put Ramblr back into
     * `enabled_accessibility_services`, preserving other services' entries (e.g. Tasker's)
     * verbatim. Base tier can't do this -- its banner deep-links to the service's Settings page
     * instead.
     */
    fun reEnableService(context: Context): Boolean =
        setBinding(context, KEY_ENABLED_SERVICES, bound = true)
}
