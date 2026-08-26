package com.trevornk.ramblr

/**
 * Pure logic for the invocation-method chooser and the #156 "service killed by the OS shortcut
 * toggle" guard rail. No Android dependencies, so every rule here is unit-tested
 * ([InvocationMethodsTest]) -- the same reason [SettingsSubtitles]' formatters are free
 * functions.
 *
 * Background (#156 root-cause memo + dual-component rework): a service with targetSdk > Q and
 * FLAG_REQUEST_ACCESSIBILITY_BUTTON is classified INVISIBLE_TOGGLE by the OS, which makes
 * AccessibilityManagerService couple the service's enabled state to its OS shortcut bindings:
 * removing the LAST shortcut (nav-bar/floating button, gesture, volume-keys, QS a11y tile)
 * disables the whole service, and adding one re-enables it. The flag is static-XML-only, so
 * Ramblr ships TWO service components -- [WhisperAccessibilityService] without the flag (plain
 * TOGGLE class, ring-only, the default) and [SystemControlsAccessibilityService] with it -- and
 * PM-enables exactly one ([InvocationServiceMode]). The kill sync runs only through the
 * framework's enableShortcutsForTargets() path -- raw Settings.Secure writes bypass it
 * (device-verified), which is what the optional WRITE_SECURE_SETTINGS tier exploits
 * ([InvocationSecureSettings]).
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
 * Returns [list] with [oldComponent] replaced by [newComponent] -- the #156 dual-component mode
 * switch's read-modify-write on `enabled_accessibility_services`. Rules, each pinned in tests:
 *
 *  - [oldComponent] is replaced IN PLACE (first occurrence's position), so other apps' entries
 *    (e.g. Tasker's) keep their exact positions and spellings.
 *  - Both flatten forms of [oldComponent] match; any further duplicates of it are dropped.
 *  - If [newComponent] is already present (either form), the result is deduped -- the swap never
 *    yields both of our components enabled at once.
 *  - If [oldComponent] is absent, [newComponent] is appended last (the position the OS's own
 *    ShortcutUtils uses when it adds entries) -- the mode switch still lands the user in a
 *    working state even when the old component had already been disabled out from under us.
 */
fun componentListReplace(list: String?, oldComponent: String, newComponent: String): String {
    val entries = componentListEntries(list)
    val result = mutableListOf<String>()
    var replaced = false
    for (entry in entries) {
        when {
            componentEquals(entry, oldComponent) -> {
                // First occurrence becomes the new component (unless it's already in the
                // result); later duplicates are dropped entirely.
                if (!replaced && result.none { componentEquals(it, newComponent) }) {
                    result += newComponent
                }
                replaced = true
            }
            componentEquals(entry, newComponent) -> {
                // Already-present new component: keep one occurrence, verbatim.
                if (result.none { componentEquals(it, newComponent) }) result += entry
            }
            else -> result += entry
        }
    }
    if (result.none { componentEquals(it, newComponent) }) result += newComponent
    return result.joinToString(COMPONENT_LIST_SEPARATOR)
}

// --- #156 dual-component mode resolution ----------------------------------------------------

/**
 * The two invocation "worlds" of the #156 dual-component design, each a separate manifest
 * `<service>` because `flagRequestAccessibilityButton` is static-XML-only yet decides the OS
 * classification (TOGGLE vs INVISIBLE_TOGGLE). Exactly one component is PM-enabled at a time.
 */
enum class InvocationMode {
    /** [WhisperAccessibilityService], no button flag: ring-only, independent Settings switch,
     *  no invisible-toggle trap. The default -- and what every pre-dual install already has in
     *  `enabled_accessibility_services`. */
    FLOATING_ICON,

    /** [SystemControlsAccessibilityService], button flag declared: system a11y button/gesture/
     *  volume-keys invoke dictation, at the cost of the INVISIBLE_TOGGLE shortcut-service
     *  coupling (which the guard rail watches). Opt-in, PM-disabled by default. */
    SYSTEM_CONTROLS,
}

/**
 * Which mode the install is in, from the one bit that decides it: whether the system-controls
 * component is PM-enabled. The floating component's own PM state is deliberately NOT an input --
 * the switch sequence enables the target before disabling the old component, so a crash between
 * those steps can briefly leave both enabled, and "system component on" must win during that
 * window for the retry/recovery paths to converge.
 */
fun resolveInvocationMode(systemComponentPmEnabled: Boolean): InvocationMode =
    if (systemComponentPmEnabled) InvocationMode.SYSTEM_CONTROLS else InvocationMode.FLOATING_ICON

/**
 * The #156 guard-rail decision: should the "Ramblr was turned off by the system shortcut
 * switch" recovery banner be showing right now?
 *
 * [systemControlsModeActive] gates the whole thing (dual-component rework): only the
 * system-controls component is INVISIBLE_TOGGLE-classified, so only it is subject to the
 * "last shortcut off kills the service" sync. In floating-icon mode the service is an ordinary
 * TOGGLE service with an independent Settings switch -- a disabled service there is a user
 * choice, not the trap, and the banner must stay quiet.
 *
 * The state it detects is specific: the service used to be connected ([serviceWasEnabled], a pref
 * the service itself writes in onServiceConnected), it is no longer in the OS's
 * `enabled_accessibility_services` ([serviceEnabledNow], checked for the ACTIVE component), and
 * no OS shortcut binding remains ([anyShortcutBound] -- button/gesture targets and the
 * volume-keys target both empty of Ramblr). That combination is exactly what the
 * invisible-toggle sync leaves behind when the user turns off the last "Ramblr shortcut" switch
 * in system Settings. A plain "user never enabled the service" fresh install has
 * [serviceWasEnabled] false and shows nothing.
 *
 * [bannerDismissed] keeps it non-nagging: dismissing persists until the service next connects
 * (the service clears the dismissal in onServiceConnected via
 * [InvocationGuardRail.recordServiceConnected]), so the banner reappears only on a fresh
 * detection, never as a repeat nag for the same one.
 */
fun shouldShowServiceKilledBanner(
    systemControlsModeActive: Boolean,
    serviceWasEnabled: Boolean,
    serviceEnabledNow: Boolean,
    anyShortcutBound: Boolean,
    bannerDismissed: Boolean,
): Boolean = systemControlsModeActive && serviceWasEnabled && !serviceEnabledNow &&
    !anyShortcutBound && !bannerDismissed

// --- Subtitle / status formatters (SettingsSubtitles pattern: pure, shared by build + refresh) ---

/**
 * The main settings screen's "Invocation" category-row subtitle: leads with the active mode,
 * then the methods that are ON so the row doubles as a status line.
 */
fun invocationMainRowSubtitleText(
    mode: InvocationMode,
    ringVisible: Boolean,
    systemButtonBound: Boolean,
    volumeKeysBound: Boolean,
): String {
    val modeName = when (mode) {
        InvocationMode.FLOATING_ICON -> "Floating icon mode"
        InvocationMode.SYSTEM_CONTROLS -> "System controls mode"
    }
    val active = buildList {
        if (ringVisible) add("ring on")
        if (mode == InvocationMode.SYSTEM_CONTROLS && systemButtonBound) add("system button on")
        if (mode == InvocationMode.SYSTEM_CONTROLS && volumeKeysBound) add("volume keys on")
    }
    return if (active.isEmpty()) "$modeName — no method currently active"
    else "$modeName — ${active.joinToString(", ")}"
}

/** The mode chooser's "Floating icon" card subtitle. */
fun invocationFloatingModeSubtitleText(active: Boolean): String =
    if (active) "Active — Ramblr's own floating ring starts dictation; the service has a " +
        "simple independent on/off switch in system Settings"
    else "Ramblr's floating ring only — no system button, and the system Settings switch " +
        "can't be tripped by shortcut changes"

/**
 * The mode chooser's "System controls" card subtitle. [directControl] is whether the
 * WRITE_SECURE_SETTINGS tier is active (switching modes is seamless) as opposed to the guided
 * path (one enable tap in system Settings after switching).
 */
fun invocationSystemModeSubtitleText(active: Boolean, directControl: Boolean): String =
    if (active) "Active — the system accessibility button, gesture, or volume-keys hold starts dictation"
    else "Nav-bar/floating button, accessibility gesture, and volume-keys hold" +
        if (directControl) " — switches instantly" else " — needs one enable tap in system Settings"

/** The chooser's "Floating ring" toggle subtitle -- available in BOTH modes (someone in system
 *  mode may still want the ring), but defaulted off on the switch INTO system mode to avoid the
 *  double-icon problem. */
fun invocationRingSubtitleText(visible: Boolean): String =
    if (visible) "On — tap the ring to start and stop dictation"
    else "Off — the ring is hidden"

/**
 * The system-mode "System accessibility button / gesture" row subtitle. Only meaningful while
 * [modeActive]; in floating-icon mode the row is inert and says why. [bound] is whether the
 * system component is in `accessibility_button_targets`; [directControl] is whether the
 * WRITE_SECURE_SETTINGS tier is active (toggle applies in-app) as opposed to the deep-link path
 * (toggle opens system Settings).
 */
fun invocationSystemButtonSubtitleText(modeActive: Boolean, bound: Boolean, directControl: Boolean): String {
    if (!modeActive) return "Requires System controls mode"
    val state = if (bound) "On — the system button/gesture starts dictation" else "Off"
    val how = if (directControl) "tap to switch in-app" else "tap to open system settings"
    return "$state · $how"
}

/**
 * The system-mode "Volume-keys hold" row subtitle. Only offered in system-controls mode, and
 * not just for tidiness: for the floating-icon component (an ordinary TOGGLE-class service,
 * no button flag) the OS's volume-keys shortcut semantics are "toggle the service on/off"
 * (AMS.java 4296-4306) -- binding it there would make the volume keys silently kill and revive
 * Ramblr instead of starting dictation. Only the button-flag (INVISIBLE_TOGGLE) component gets
 * the "fire the a11y-button callback" semantics that make this shortcut an invoker.
 */
fun invocationVolumeKeysSubtitleText(modeActive: Boolean, bound: Boolean, directControl: Boolean): String {
    if (!modeActive) return "Requires System controls mode — in Floating icon mode this " +
        "shortcut would turn Ramblr itself off and on"
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
