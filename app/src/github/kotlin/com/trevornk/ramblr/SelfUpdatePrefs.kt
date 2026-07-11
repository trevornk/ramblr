package com.trevornk.ramblr

import android.content.Context

/**
 * Two on-device toggles for the GitHub self-update feature (Part 3, Settings UI), backed by the
 * same plain "ramblr" SharedPreferences file as every other toggle in the app (see
 * [RawTextRetryToggle]/[AutoPeekToggle]/[HideIconToggle] for the identical pattern). Lives in
 * src/github/ (not src/main/) for the same Google Play policy reason as [SelfUpdateChecker] --
 * see its AGENTS note.
 */
object SelfUpdatePrefs {
    private const val PREFS_NAME = "ramblr"

    /** Default ON: the whole point of this feature is to tell Trevor a new build exists; opting
     *  out should be a deliberate action, not the default. */
    const val KEY_NOTIFY_ENABLED = "self_update_notify_enabled"
    private const val DEFAULT_NOTIFY_ENABLED = true

    /** Default OFF: silently installing an APK over a running app is a much bigger action than
     *  just notifying, so this stays opt-in even though it's nested under (and requires) the
     *  notify toggle above. */
    const val KEY_AUTO_INSTALL_ENABLED = "self_update_auto_install_enabled"
    private const val DEFAULT_AUTO_INSTALL_ENABLED = false

    fun isNotifyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ENABLED, DEFAULT_NOTIFY_ENABLED)

    fun setNotifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ENABLED, enabled).apply()
        // TODO(Part 5): schedule/cancel the periodic SelfUpdateCheckWorker here (enable when
        // `enabled` is true, cancel when false). Not built yet -- see SelfUpdateChecker.kt's
        // kdoc on the periodic job this pref is meant to gate.
    }

    fun isAutoInstallEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_INSTALL_ENABLED, DEFAULT_AUTO_INSTALL_ENABLED)

    fun setAutoInstallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_INSTALL_ENABLED, enabled).apply()
        // TODO(Part 4/5): nothing to wire yet -- the install pipeline (Part 4) and periodic
        // worker (Part 5) are the ones that will actually read this flag to decide whether to
        // auto-install a downloaded update vs. just notifying about it.
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
