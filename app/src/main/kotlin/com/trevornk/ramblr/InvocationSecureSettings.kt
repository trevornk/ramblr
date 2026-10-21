package com.trevornk.ramblr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The Android side of the invocation screen's OS-shortcut state: reads the three
 * `Settings.Secure` accessibility component lists (readable by any app, no permission) and --
 * only when the optional WRITE_SECURE_SETTINGS tier is granted via adb (#156) -- writes them.
 *
 * Dual-component aware (#156 rework): Ramblr ships two service components
 * ([WhisperAccessibilityService] / [SystemControlsAccessibilityService], see
 * [InvocationServiceMode]), and the OS lists may name either -- e.g. an install that predates
 * the rework still has the old component bound in `accessibility_button_targets`. All READS
 * therefore match EITHER component ("is any Ramblr service/binding present"), while WRITES that
 * add an entry always use the currently-active component, and writes that remove clean up both.
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
     * fallback to ACTION_ACCESSIBILITY_SETTINGS (via [openServiceDetailsSettings]) for OEM skins
     * that don't export it or guard it with a permission.
     */
    const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /**
     * Deep-links to the ACTIVE component's own Accessibility page, falling back to the top-level
     * Accessibility list. The single shared implementation behind every "open Ramblr's service
     * page" call site (#344).
     *
     * The fallback fires on [ActivityNotFoundException] (OEM skins that don't export the details
     * action) AND on [SecurityException]: ACCESSIBILITY_DETAILS_SETTINGS is `@hide`, and some
     * builds (e.g. Samsung One UI on Android 16) guard it with the signature-level
     * `android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS`, which a third-party app can never
     * hold -- `startActivity` then throws instead of failing to resolve. The top-level list uses
     * the public, unguarded `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
     *
     * [newTask] adds FLAG_ACTIVITY_NEW_TASK to whichever intent is launched (needed when
     * [context] isn't an Activity, or when the caller is a trampoline that finishes itself).
     * [component] is the flattened service component to target; defaults to the active one.
     */
    fun openServiceDetailsSettings(
        context: Context,
        newTask: Boolean = false,
        component: String = serviceComponent(context),
    ) {
        fun Intent.withFlags() = if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else this
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .withFlags()
        try {
            context.startActivity(details)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Accessibility details page not resolvable; falling back to the list", e)
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).withFlags())
        } catch (e: SecurityException) {
            Log.w(TAG, "Accessibility details page is permission-guarded; falling back to the list", e)
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).withFlags())
        }
    }

    /** The ACTIVE service component (per the current #156 mode) in the full flatten form the OS
     *  itself writes -- what new bindings and deep links must target. */
    fun serviceComponent(context: Context): String =
        InvocationServiceMode.activeComponent(context).flattenToString()

    /** Whether the optional advanced tier is active: `pm grant`-ed WRITE_SECURE_SETTINGS. */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // --- Reads (no permission needed; match EITHER Ramblr component -- see class kdoc) ---

    /** Whether [list] contains ANY Ramblr service component, in either flatten form. */
    private fun anyRamblrIn(context: Context, list: String?): Boolean =
        InvocationServiceMode.allComponents(context).any { componentListContains(list, it) }

    fun isButtonTargetBound(context: Context): Boolean =
        anyRamblrIn(context, read(context, KEY_BUTTON_TARGETS))

    fun isVolumeKeysBound(context: Context): Boolean =
        anyRamblrIn(context, read(context, KEY_SHORTCUT_TARGET_SERVICE))

    /** Whether ANY Ramblr component is in `enabled_accessibility_services` -- the health check
     *  used app-wide (setup rows, onboarding, guard rail) since either component counts as
     *  "the service is enabled". */
    fun isServiceEnabled(context: Context): Boolean =
        anyRamblrIn(context, read(context, KEY_ENABLED_SERVICES))

    /** Whether the component the CURRENT mode actually uses is enabled (#258). Distinct from
     *  [isServiceEnabled]: a stale automation entry naming the other component makes that
     *  return true while Ramblr is in fact not running. */
    fun isActiveComponentEnabled(context: Context): Boolean =
        componentListContains(read(context, KEY_ENABLED_SERVICES), serviceComponent(context))

    /** Whether the component the current mode does NOT use is enabled (#258) -- the signature of
     *  an automation macro holding a stale component. AMS drops such an entry when it names the
     *  PM-disabled component, which is what takes Ramblr out of the list entirely. */
    fun isInactiveComponentEnabled(context: Context): Boolean {
        val active = serviceComponent(context)
        return InvocationServiceMode.allComponents(context)
            .filterNot { componentEquals(it, active) }
            .any { componentListContains(read(context, KEY_ENABLED_SERVICES), it) }
    }

    /**
     * Replaces a stale INACTIVE-component entry with the active one, or adds the active one when
     * Ramblr was stripped from the list entirely (#258). Other apps' entries are preserved
     * verbatim by [componentListReplace]/[componentListAdd]. Advanced tier only -- returns false
     * so the caller can fall back to the recovery banner.
     */
    fun repairToActiveComponent(context: Context): Boolean {
        if (!canWrite(context)) return false
        val active = serviceComponent(context)
        val current = read(context, KEY_ENABLED_SERVICES)
        val stale = InvocationServiceMode.allComponents(context)
            .firstOrNull { !componentEquals(it, active) && componentListContains(current, it) }
        val updated = if (stale != null) {
            componentListReplace(current, stale, active)
        } else {
            componentListAdd(current, active)
        }
        return try {
            Settings.Secure.putString(context.contentResolver, KEY_ENABLED_SERVICES, updated)
        } catch (e: SecurityException) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS repair of $KEY_ENABLED_SERVICES failed despite grant", e)
            false
        }
    }

    /** Whether ANY OS shortcut still binds Ramblr -- the guard rail's "targets empty" input. */
    fun anyShortcutBound(context: Context): Boolean =
        isButtonTargetBound(context) || isVolumeKeysBound(context)

    private fun read(context: Context, key: String): String? =
        Settings.Secure.getString(context.contentResolver, key)

    // --- Writes (advanced tier only; all return success and never throw) ---

    /**
     * Adds/removes Ramblr in [key]'s component list, preserving every other app's entries
     * verbatim (read-modify-write through the tested pure helpers). Adding uses the ACTIVE
     * component; removing sweeps BOTH components so stale pre-rework bindings (which name the
     * old component) get cleaned up by the same tap. Returns false -- caller falls back to the
     * deep-link flow -- if the permission is missing or the write throws.
     */
    fun setBinding(context: Context, key: String, bound: Boolean): Boolean {
        if (!canWrite(context)) return false
        val current = read(context, key)
        val updated = if (bound) {
            componentListAdd(current, serviceComponent(context))
        } else {
            InvocationServiceMode.allComponents(context)
                .fold(current as String?) { list, component -> componentListRemove(list, component) }
                .orEmpty()
        }
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
     * The advanced tier's true one-tap re-enable (#156 guard rail): put the ACTIVE component
     * back into `enabled_accessibility_services`, preserving other services' entries (e.g.
     * Tasker's) verbatim. Base tier can't do this -- its banner deep-links to the service's
     * Settings page instead.
     */
    fun reEnableService(context: Context): Boolean =
        setBinding(context, KEY_ENABLED_SERVICES, bound = true)
}
