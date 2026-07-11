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
        // Part 5: the periodic check is entirely gated by this toggle -- there's no separate
        // "master" switch, so flipping this is exactly when the periodic job should start/stop
        // existing at all. schedule()'s KEEP policy makes turning it on twice a harmless no-op.
        if (enabled) SelfUpdateCheckWorker.schedule(context) else SelfUpdateCheckWorker.cancel(context)
    }

    fun isAutoInstallEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_INSTALL_ENABLED, DEFAULT_AUTO_INSTALL_ENABLED)

    fun setAutoInstallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_INSTALL_ENABLED, enabled).apply()
        // Part 5: the per-tick "should I auto-install" decision lives in the periodic
        // SelfUpdateCheckWorker (see SelfUpdateCheckDecision.shouldAutoInstall), not here -- this
        // setter only fires on a settings-screen toggle, not on every check.
        //
        // Design decision: when auto-install is switched ON and an UpdateAvailable result is
        // *already* cached from a previous check, enqueue the install immediately instead of
        // making the user wait up to 6h for the next periodic tick. A user who just opted into
        // auto-install with a known-available update sitting in cache almost certainly wants it
        // applied now, not on the next scheduled tick -- waiting silently would look like the
        // toggle didn't do anything. No equivalent immediate action is needed when turning it
        // OFF: SelfUpdateInstallWorker.cancel(context) would race an install that's already
        // mid-flight (download/verify/gate) for no benefit, since the worker itself re-reads
        // isAutoInstallEnabled per tick and simply won't be re-enqueued going forward.
        if (enabled) {
            val cached = SelfUpdateChecker.cachedResult(context)
            if (cached is UpdateCheckResult.UpdateAvailable) {
                SelfUpdateInstallWorker.enqueue(context)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
