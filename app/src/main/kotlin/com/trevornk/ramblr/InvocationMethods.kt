package com.trevornk.ramblr

/**
 * Pure logic for the invocation-method chooser and the #156 "service killed by the OS shortcut
 * toggle" guard rail. No Android dependencies, so every rule here is unit-tested
 * ([InvocationMethodsTest]) -- the same reason [SettingsSubtitles]' formatters are free
 * functions.
 *
 * Background (#156 root-cause memo): Ramblr's service is classified INVISIBLE_TOGGLE by the OS
 * (targetSdk > Q + FLAG_REQUEST_ACCESSIBILITY_BUTTON), so AccessibilityManagerService couples the
 * service's enabled state to its OS shortcut bindings: removing the LAST shortcut (nav-bar/
 * floating button, gesture, volume-keys, QS a11y tile) disables the whole service, and adding one
 * re-enables it. That sync runs only through the framework's enableShortcutsForTargets() path --
 * raw Settings.Secure writes bypass it (device-verified), which is what the optional
 * WRITE_SECURE_SETTINGS tier exploits ([InvocationSecureSettings]).
 *
 * The OS stores shortcut bindings as colon-separated flattened-ComponentName lists in
 * Settings.Secure (`accessibility_button_targets`, `accessibility_shortcut_target_service`,
 * `enabled_accessibility_services`). The helpers below implement the read-modify-write editing of
 * those lists. Two invariants matter enough to pin in tests:
 *
 *  - OTHER apps' entries are preserved byte-for-byte. Clobbering another accessibility tool's
 *    binding (e.g. Tasker's) while toggling ours would be a serious, hard-to-diagnose bug on the
 *    user's device.
 *  - Component names come in two flatten forms -- full (`pkg/pkg.Cls`) and short (`pkg/.Cls`) --
 *    and the OS mixes them freely. Matching is normalized so `com.trevornk.ramblr/
 *    .WhisperAccessibilityService` and `com.trevornk.ramblr/com.trevornk.ramblr
 *    .WhisperAccessibilityService` are the same entry.
 */

/** Separator the OS uses between entries in the accessibility component-list settings. */
const val COMPONENT_LIST_SEPARATOR = ":"

/**
 * Expands the short flatten form (`pkg/.Cls` -> `pkg/pkg.Cls`) so the two spellings of the same
 * component compare equal. Entries that aren't `pkg/cls`-shaped are returned unchanged -- the
 * list helpers must tolerate junk without corrupting it.
 */
fun normalizeComponent(component: String): String {
    val slash = component.indexOf('/')
    if (slash <= 0 || slash == component.length - 1) return component
    val pkg = component.substring(0, slash)
    val cls = component.substring(slash + 1)
    return if (cls.startsWith(".")) "$pkg/$pkg$cls" else component
}

/** Whether two flattened component names refer to the same component, in either flatten form. */
fun componentEquals(a: String, b: String): Boolean = normalizeComponent(a) == normalizeComponent(b)

/**
 * Splits a colon-separated component list into its entries, verbatim (no normalization), dropping
 * empty segments (a null/blank setting, doubled or stray separators).
 */
fun componentListEntries(list: String?): List<String> =
    list.orEmpty().split(COMPONENT_LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

/** Whether [component] (in either flatten form) is present in the colon-separated [list]. */
fun componentListContains(list: String?, component: String): Boolean =
    componentListEntries(list).any { componentEquals(it, component) }

/**
 * Returns [list] with [component] added (no-op when already present in either flatten form).
 * Every other entry is preserved verbatim, in order; the new entry is appended last, which is
 * where the OS's own ShortcutUtils appends too.
 */
fun componentListAdd(list: String?, component: String): String {
    val entries = componentListEntries(list)
    if (entries.any { componentEquals(it, component) }) {
        return entries.joinToString(COMPONENT_LIST_SEPARATOR)
    }
    return (entries + component).joinToString(COMPONENT_LIST_SEPARATOR)
}

/**
 * Returns [list] with every occurrence of [component] (in either flatten form) removed. Every
 * other entry is preserved verbatim, in order. Removing the last entry yields `""` -- the OS
 * writes the empty string (not null) for an emptied target list, so that's what callers persist.
 */
fun componentListRemove(list: String?, component: String): String =
    componentListEntries(list)
        .filterNot { componentEquals(it, component) }
        .joinToString(COMPONENT_LIST_SEPARATOR)

/**
 * The #156 guard-rail decision: should the "Ramblr was turned off by the system shortcut switch"
 * recovery banner be showing right now?
 *
 * The state it detects is specific: the service used to be connected ([serviceWasEnabled], a pref
 * the service itself writes in onServiceConnected), it is no longer in the OS's
 * `enabled_accessibility_services` ([serviceEnabledNow]), and no OS shortcut binding remains
 * ([anyShortcutBound] -- button/gesture targets and the volume-keys target both empty of Ramblr).
 * That combination is exactly what the invisible-toggle sync leaves behind when the user turns
 * off the last "Ramblr shortcut" switch in system Settings. A plain "user never enabled the
 * service" fresh install has [serviceWasEnabled] false and shows nothing.
 *
 * [bannerDismissed] keeps it non-nagging: dismissing persists until the service next connects
 * (the service clears the dismissal in onServiceConnected via
 * [InvocationGuardRail.recordServiceConnected]), so the banner reappears only on a fresh
 * detection, never as a repeat nag for the same one.
 */
fun shouldShowServiceKilledBanner(
    serviceWasEnabled: Boolean,
    serviceEnabledNow: Boolean,
    anyShortcutBound: Boolean,
    bannerDismissed: Boolean,
): Boolean = serviceWasEnabled && !serviceEnabledNow && !anyShortcutBound && !bannerDismissed

// --- Subtitle / status formatters (SettingsSubtitles pattern: pure, shared by build + refresh) ---

/**
 * The main settings screen's "Invocation" category-row subtitle: leads with the methods that are
 * ON so the row doubles as a status line, falling back to the pitch line when only the default
 * ring is active.
 */
fun invocationMainRowSubtitleText(
    ringVisible: Boolean,
    systemButtonBound: Boolean,
    volumeKeysBound: Boolean,
): String {
    val active = buildList {
        if (ringVisible) add("Floating ring")
        if (systemButtonBound) add("System button")
        if (volumeKeysBound) add("Volume keys")
    }
    return if (active.isEmpty()) "How to start dictation — no method currently active"
    else "How to start dictation — ${active.joinToString(", ")} on"
}

/** The chooser's "Floating ring" row subtitle. */
fun invocationRingSubtitleText(visible: Boolean): String =
    if (visible) "On — tap the ring to start and stop dictation"
    else "Off — the ring is hidden"

/**
 * The chooser's "System accessibility button / gesture" row subtitle. [bound] is whether Ramblr
 * is in `accessibility_button_targets`; [directControl] is whether the WRITE_SECURE_SETTINGS
 * tier is active (toggle applies in-app) as opposed to the deep-link path (toggle opens system
 * Settings).
 */
fun invocationSystemButtonSubtitleText(bound: Boolean, directControl: Boolean): String {
    val state = if (bound) "On — the system button/gesture starts dictation" else "Off"
    val how = if (directControl) "tap to switch in-app" else "tap to open system settings"
    return "$state · $how"
}

/** The chooser's "Volume-keys hold" row subtitle. [bound] is whether Ramblr is
 *  `accessibility_shortcut_target_service`'s target. */
fun invocationVolumeKeysSubtitleText(bound: Boolean, directControl: Boolean): String {
    val state = if (bound) "On — hold both volume keys to start dictation" else "Off"
    val how = if (directControl) "tap to switch in-app" else "tap to open system settings"
    return "$state · $how"
}

/** The chooser's "Quick Settings tile" row subtitle. The OS gives an app no way to read whether
 *  its tile has been added to the panel, so this is a how-to, not a status. [canRequestAdd] is
 *  API 33+, where the one-tap system "add this tile?" prompt exists. */
fun invocationQsTileSubtitleText(canRequestAdd: Boolean): String =
    if (canRequestAdd) "Dictate from the Quick Settings panel — tap to add the tile"
    else "Dictate from the Quick Settings panel — tap for setup steps"

/** The advanced tier's row subtitle: granted state is feature-detected, never assumed. */
fun invocationAdvancedTierSubtitleText(granted: Boolean): String =
    if (granted) "Active — shortcut changes apply in-app, without the system Settings round-trip"
    else "Lets Ramblr manage system shortcuts directly — requires a one-time adb grant, tap for the command"

/** The exact adb command the advanced tier needs, shown in its dialog for copy/paste. */
fun wssAdbCommand(packageName: String): String =
    "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
