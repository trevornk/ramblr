package com.trevornk.ramblr

import android.content.Context
import android.os.Build

/**
 * The notification permission is asked for automatically only once. Before, every launch of the
 * app re-asked it for as long as it wasn't granted: dismissing the prompt (back, or a tap outside)
 * doesn't count as a denial, so Android never stopped showing it. After that one ask, Setup's
 * "Notifications" row is where to turn them on.
 */
internal object NotificationPermissionAsk {
    const val KEY_ASKED = "notification_permission_asked"

    fun shouldAutoAsk(sdk: Int, granted: Boolean, alreadyAsked: Boolean): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU && !granted && !alreadyAsked

    fun alreadyAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** The Setup row's subtitle. */
    fun status(granted: Boolean): String =
        if (granted) "On — download progress, results after you leave an app, and alerts"
        else "Off — tap to turn on download progress, results after you leave an app, and alerts"

    private fun prefs(context: Context) = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
}
