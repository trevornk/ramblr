package com.trevornk.ramblr

/**
 * The "System controls" mode component of the #156 dual-component design: byte-for-byte the
 * same service as [WhisperAccessibilityService] (an empty subclass -- every override, pref,
 * overlay, and the [WhisperAccessibilityService.instance] static all come from the base class,
 * which never assumes its own concrete component name), declared as a SECOND `<service>` in the
 * manifest whose meta-data XML (`accessibility_service_config_system.xml`) adds
 * `flagRequestAccessibilityButton`.
 *
 * Why a whole separate component for one XML flag: the flag is static-only (runtime writes are
 * ignored for targetSdk > 29) and it is what classifies a service INVISIBLE_TOGGLE vs TOGGLE in
 * the OS -- i.e. it simultaneously (a) enables the system a11y button / gesture / volume-keys
 * invocation surfaces and (b) welds the service's enabled state to its shortcut bindings, so
 * removing the last shortcut in system Settings kills the service (#156). Since one class can't
 * be both classifications, Ramblr ships both components and PM-enables exactly one
 * ([InvocationServiceMode]): this one only when the user explicitly opts into system controls
 * on the Invocation screen, accepting the coupling that the guard-rail banner then watches for.
 *
 * The [WhisperAccessibilityService.accessibilityButtonCallback] registration in the base class
 * is what receives the button/gesture/volume-keys events here; on the base component the same
 * registration is a harmless no-op because without the flag the OS never routes a button event
 * to the service.
 */
class SystemControlsAccessibilityService : WhisperAccessibilityService()
