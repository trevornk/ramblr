package com.trevornk.ramblr

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Optional "open an app" shortcut in the floating bubble's long-press menu. Off by default: with no
 * app chosen nothing is added to the menu and no behavior changes.
 *
 * The chosen app is stored as an explicit [ComponentName] and launched with an explicit intent --
 * no action, no data, no extras -- so nothing Ramblr knows (transcript text, the app being
 * dictated into) is ever handed to it. It does not start a dictation and never touches the
 * dictation session lease: it is a plain app launcher.
 *
 * If the chosen app is later uninstalled (or its activity is removed/disabled) [resolve] returns
 * null, the Settings row shows "Not set" and the menu item is hidden.
 */
object BubbleShortcut {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "bubble_shortcut_component"

    /** An app the user can pick: its launchable [component] and a display [label]. */
    data class Target(val component: ComponentName, val label: String)

    fun stored(context: Context): ComponentName? = parse(prefs(context).getString(KEY, null))

    fun set(context: Context, component: ComponentName?) {
        prefs(context).edit().apply {
            if (component == null) remove(KEY) else putString(KEY, component.flattenToString())
        }.apply()
    }

    /** The stored app if it is still installed and launchable, else null (setting off or app gone). */
    fun resolve(context: Context): Target? {
        val component = stored(context) ?: return null
        return try {
            val pm = context.packageManager
            val info = pm.getActivityInfo(component, 0)
            if (!info.enabled || !info.exported) null else Target(component, info.loadLabel(pm).toString())
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Explicit, extras-free launch. Returns false if the app could not be started. */
    fun launch(context: Context, component: ComponentName): Boolean = try {
        context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Launchable apps (MAIN/LAUNCHER) plus apps exposing an assistant entry point (ASSIST,
     * VOICE_COMMAND), de-duplicated and sorted by label. Ramblr itself is left out. Only visible
     * through the narrow `<queries>` entries in the manifest -- no QUERY_ALL_PACKAGES.
     */
    fun candidates(context: Context): List<Target> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_VOICE_COMMAND),
        )
        return intents
            .flatMap { pm.queryIntentActivities(it, 0) }
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName || !ai.exported) return@mapNotNull null
                Target(ComponentName(ai.packageName, ai.name), ri.loadLabel(pm).toString())
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    internal fun parse(flat: String?): ComponentName? =
        flat?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
