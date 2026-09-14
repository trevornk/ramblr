package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #254 investigation: pure unit coverage for the read-only diagnostic snapshot format
 * ([RamblrDiagnosticSnapshot]/[formatDiagnosticSnapshot]) added alongside the rapid/overlapping
 * on-device restore matrix (scripts/254_rapid_matrix.sh). No Robolectric/Android dependency --
 * this is a plain data-class + string-formatting function, matching the existing
 * pure-function-unit-test pattern used for [resolveAutomationOff]/[resultCodeFor] in this same
 * package (see AutomationOffHookTest, if present, or AutomationOffReceiver's own kdoc for the
 * pure/impure split rationale).
 */
class RamblrDiagnosticSnapshotTest {

    @Test
    fun `formats all fields as stable key=value pairs`() {
        val snapshot = RamblrDiagnosticSnapshot(
            serviceInstanceConnected = true,
            activeComponentEnabledInSettings = true,
            inactiveComponentEnabledInSettings = false,
            automationOffHookEnabled = true,
            writeSecureSettingsGranted = false,
        )
        assertEquals(
            "instance_connected=true;active_component_enabled=true;" +
                "inactive_component_enabled=false;automation_off_hook_enabled=true;" +
                "write_secure_settings_granted=false",
            formatDiagnosticSnapshot(snapshot),
        )
    }

    @Test
    fun `distinguishes stale-inactive-component from genuinely-not-enabled`() {
        // #258's failure signature: active component missing, inactive one still present.
        val staleComponent = RamblrDiagnosticSnapshot(
            serviceInstanceConnected = false,
            activeComponentEnabledInSettings = false,
            inactiveComponentEnabledInSettings = true,
            automationOffHookEnabled = true,
            writeSecureSettingsGranted = true,
        )
        // A genuinely-off service: neither component present.
        val genuinelyOff = RamblrDiagnosticSnapshot(
            serviceInstanceConnected = false,
            activeComponentEnabledInSettings = false,
            inactiveComponentEnabledInSettings = false,
            automationOffHookEnabled = true,
            writeSecureSettingsGranted = true,
        )
        assert(formatDiagnosticSnapshot(staleComponent) != formatDiagnosticSnapshot(genuinelyOff))
    }
}
