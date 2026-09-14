package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #257 automation off-hook policy. The gate is the whole security story for an exported
 * receiver, so it is pinned here rather than left to a device test.
 */
class AutomationOffHookTest {

    @Test
    fun `hook disabled ignores the broadcast even when the service is running`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_DISABLED,
            resolveAutomationOff(hookEnabled = false, serviceConnected = true),
        )
    }

    @Test
    fun `hook disabled ignores the broadcast when the service is not running`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_DISABLED,
            resolveAutomationOff(hookEnabled = false, serviceConnected = false),
        )
    }

    @Test
    fun `hook enabled with a connected service disables it`() {
        assertEquals(
            AutomationOffOutcome.DISABLE,
            resolveAutomationOff(hookEnabled = true, serviceConnected = true),
        )
    }

    @Test
    fun `hook enabled with no connected service is a no-op`() {
        assertEquals(
            AutomationOffOutcome.IGNORED_NOT_RUNNING,
            resolveAutomationOff(hookEnabled = true, serviceConnected = false),
        )
    }

    /**
     * The disabled check must come first, so an outside caller cannot use the outcome to learn
     * whether Ramblr's service is running while the hook is off.
     */
    @Test
    fun `disabled hook reports the same outcome regardless of service state`() {
        assertEquals(
            resolveAutomationOff(hookEnabled = false, serviceConnected = true),
            resolveAutomationOff(hookEnabled = false, serviceConnected = false),
        )
    }

    // --- ordered-broadcast result-code mapping ------------------------------------------------
    //
    // `am broadcast` always sends an ordered broadcast and prints `result=<code>`, but the
    // receiver never called setResultCode(), so every outcome -- gate disabled, no live service,
    // or a real disable -- printed the same default result=0. That made a genuinely accepted
    // disable indistinguishable from a silent no-op, which is exactly backwards: the *disabled*
    // gate is the one outcome that must stay silent (no service-state disclosure to an arbitrary
    // caller), while an accepted disable is useful signal for the automation tool and should NOT
    // collide with the "nothing happened" code.

    @Test
    fun `disabled-gate outcome reports the same result code regardless of service state`() {
        // This is the privacy invariant: a caller that doesn't know the hook is off must not be
        // able to distinguish "hook off, service running" from "hook off, service not running"
        // by result code alone.
        assertEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(resolveAutomationOff(hookEnabled = false, serviceConnected = true)),
        )
        assertEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(resolveAutomationOff(hookEnabled = false, serviceConnected = false)),
        )
    }

    @Test
    fun `disabled-gate result code matches am broadcast's own default no-match code`() {
        // RESULT_HOOK_DISABLED must equal Android's default ordered-broadcast result (0) --
        // the same code a caller already sees for a mistyped action or missing component, so a
        // disabled hook is indistinguishable from "nothing there at all", not a new tell.
        assertEquals(0, resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED))
    }

    @Test
    fun `an accepted disable does not collide with the disabled-gate result code`() {
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(AutomationOffOutcome.DISABLE),
        )
    }

    @Test
    fun `hook-enabled-but-not-running does not collide with the disabled-gate result code`() {
        // The hook is on, so the caller already knows Ramblr's off-hook is opted in (they got
        // the command from Ramblr's own settings screen) -- telling them "nothing was running"
        // here is not a new disclosure the way it would be while the gate itself is off.
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_DISABLED),
            resultCodeFor(AutomationOffOutcome.IGNORED_NOT_RUNNING),
        )
    }

    @Test
    fun `every outcome maps to a distinct result code except the disabled gate`() {
        assertNotEquals(
            resultCodeFor(AutomationOffOutcome.IGNORED_NOT_RUNNING),
            resultCodeFor(AutomationOffOutcome.DISABLE),
        )
    }

    // --- numeric hosting user derivation ------------------------------------------------------
    //
    // The copied `am broadcast` command hardcoded no --user flag (which `am` resolves to user 0
    // from a shell context), so it only worked when the caller's own user happened to be 0.
    // userIdForUid must derive the ACTUAL hosting user from a uid, the same partitioning
    // UserHandle uses internally (uid = userId * 100000 + appId), not assume 0 and not require
    // any permission to compute. This is preventive hardening for multi-user/work-profile hosts,
    // not a reproduction of reporter #257's setup, which was a normal per-app uid on the primary
    // user (user 0).

    @Test
    fun `uid on the primary user maps to user 0`() {
        // A typical app uid on user 0, e.g. u0_a380 -- matches #257 reporter's actual uid shape.
        assertEquals(0, userIdForUid(10380))
    }

    @Test
    fun `uid on a secondary user maps to that user's numeric id`() {
        // Same appId, hosted on a non-zero user, e.g. u10_a380.
        assertEquals(10, userIdForUid(1010380))
    }

    @Test
    fun `uid on user 2 maps to 2`() {
        assertEquals(2, userIdForUid(200380))
    }

    // --- am broadcast command copy ----------------------------------------------------------
    //
    // The command shown to the user must explicitly target the numeric user hosting Ramblr, not
    // rely on am's no-flag default (user 0) or --user current (needs INTERACT_ACROSS_USERS a
    // same-user shell/automation caller does not have).

    @Test
    fun `command targets the given numeric user explicitly`() {
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 10)
        assertEquals(
            "am broadcast -a com.trevornk.ramblr.action.TURN_OFF " +
                "-n com.trevornk.ramblr/.AutomationOffReceiver --user 10",
            command,
        )
    }

    @Test
    fun `command for user 0 still targets it explicitly rather than omitting --user`() {
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 0)
        assertTrue("must not omit --user even for the primary user", command.contains("--user 0"))
    }

    @Test
    fun `command never uses --user current`() {
        // --user current resolves via USER_CURRENT (-2) and requires INTERACT_ACROSS_USERS,
        // a permission a same-user shell/automation caller does not have.
        val command = automationOffHookCommand("com.trevornk.ramblr", userId = 7)
        assertTrue(!command.contains("current"))
    }

    // --- disableServiceFromApp() return value gating --------------------------------------

    @Test
    fun `an accepted disable attempt reports RESULT_DISABLE_REQUESTED`() {
        assertEquals(RESULT_DISABLE_REQUESTED, resultCodeForDisableAttempt(disabled = true))
    }

    @Test
    fun `a disable attempt that found nothing live reports RESULT_NOT_RUNNING, not an accepted disable`() {
        // disableServiceFromApp() can return false if the live instance disappeared between the
        // receiver's serviceConnected check and the call. The result code must reflect what the
        // invocation actually returned, not what resolveAutomationOff optimistically decided.
        assertEquals(RESULT_NOT_RUNNING, resultCodeForDisableAttempt(disabled = false))
    }

    // --- #254 diagnostic command ------------------------------------------------------------

    @Test
    fun `diagnostic command targets the receiver and the given numeric user explicitly`() {
        val command = automationDiagnosticCommand("com.trevornk.ramblr", userId = 0)
        assertEquals(
            "am broadcast -a com.trevornk.ramblr.action.DIAGNOSTIC " +
                "-n com.trevornk.ramblr/.AutomationOffReceiver --user 0",
            command,
        )
    }

    @Test
    fun `diagnostic command never uses --user current`() {
        // Same cross-user-permission trap as the off command: USER_CURRENT (-2) requires
        // INTERACT_ACROSS_USERS, which is exactly the SecurityException the #254 reporter hit.
        val command = automationDiagnosticCommand("com.trevornk.ramblr", userId = 11)
        assertTrue(!command.contains("current"))
        assertTrue(command.contains("--user 11"))
    }

    @Test
    fun `diagnostic command is explicit-component, since implicit broadcasts are not delivered`() {
        // A manifest receiver does not receive implicit broadcasts on modern Android, so an
        // action-only command would silently do nothing -- indistinguishable from the feature
        // being broken.
        assertTrue(automationDiagnosticCommand("com.trevornk.ramblr", userId = 0)
            .contains("-n com.trevornk.ramblr/.AutomationOffReceiver"))
    }

    @Test
    fun `verify guidance names the field an automation macro must actually read`() {
        // The guidance is useless unless it names the exact key from formatDiagnosticSnapshot;
        // a macro parses that string literally.
        val guidance = automationReEnableVerifyGuidance()
        assertTrue(guidance.contains("active_component_enabled"))
        val snapshot = formatDiagnosticSnapshot(
            RamblrDiagnosticSnapshot(
                serviceInstanceConnected = false,
                activeComponentEnabledInSettings = false,
                inactiveComponentEnabledInSettings = false,
                automationOffHookEnabled = true,
                writeSecureSettingsGranted = false,
            )
        )
        assertTrue("guidance must name a field the snapshot actually emits",
            snapshot.contains("active_component_enabled"))
    }

    // --- privileged re-enable command (help-dialog counterpart to the off command) -----------
    //
    // automationOffHookEnableCommand documents the WRITE_SECURE_SETTINGS-gated `settings`
    // one-liner shown next to the off command. It must: target the same explicit numeric user as
    // the off command; add the given component to enabled_accessibility_services without
    // clobbering other apps' entries; be idempotent if the component is already present; and
    // fail closed (no write at all) if the read fails.

    private val component = "com.trevornk.ramblr/.WhisperAccessibilityService"

    @Test
    fun `enable command targets the given numeric user explicitly, like the off command`() {
        val command = automationOffHookEnableCommand(component, userId = 10)
        assertTrue(command.contains("--user \$U"))
        assertTrue(command.contains("U=10"))
    }

    @Test
    fun `enable command for user 0 still targets it explicitly rather than omitting --user`() {
        val command = automationOffHookEnableCommand(component, userId = 0)
        assertTrue("must not omit --user even for the primary user", command.contains("U=0"))
        assertTrue(command.contains("--user \$U"))
    }

    @Test
    fun `enable command never uses --user current`() {
        val command = automationOffHookEnableCommand(component, userId = 7)
        assertTrue(!command.contains("current"))
    }

    @Test
    fun `enable command checks the read exit code before writing`() {
        val command = automationOffHookEnableCommand(component, userId = 0)
        assertTrue(command.contains("R=\$?"))
        assertTrue(command.contains("if [ \$R -ne 0 ]"))
    }

    @Test
    fun `enable command embeds the given component and user id literally`() {
        val command = automationOffHookEnableCommand(
            "com.trevornk.ramblr/.SystemControlsAccessibilityService",
            userId = 3,
        )
        assertTrue(command.contains("C=com.trevornk.ramblr/.SystemControlsAccessibilityService"))
        assertTrue(command.contains("U=3"))
    }

    @Test
    fun `enable command never writes the whole list -- only appends via colon-join`() {
        // Guards against a future edit regressing to a blind overwrite that would drop every
        // other app's accessibility-service entry (Tasker's, TalkBack's, etc).
        val command = automationOffHookEnableCommand(component, userId = 0)
        assertTrue(command.contains("L=\${L:+\$L:}\$C"))
    }

    // --- fake-`settings`-executable shell harness ---------------------------------------------
    //
    // Runs the ACTUAL generated command against a tiny fake `settings` shell script that reads
    // and writes a plain state file, standing in for Settings.Secure without touching a real
    // device or emulator. This exercises the real string this function returns, not a
    // re-implementation of its logic, while never mutating anything outside a temp dir.

    private fun runEnableCommand(
        component: String,
        userId: Int,
        initialList: String?,
        failRead: Boolean = false,
        failWrite: Boolean = false,
    ): ShellResult {
        val dir = createTempDir(prefix = "ramblr-settings-harness")
        try {
            val stateFile = java.io.File(dir, "enabled_accessibility_services.txt")
            if (initialList != null) stateFile.writeText(initialList)

            val fakeSettings = java.io.File(dir, "settings")
            fakeSettings.writeText(
                """
                #!/bin/sh
                # Fake `settings` for the harness: only understands the two calls the generated
                # command makes (get/put secure enabled_accessibility_services, put secure
                # accessibility_enabled) behind an explicit --user flag.
                set -e
                if [ "$1" != "--user" ]; then echo "fake settings: expected --user first" >&2; exit 64; fi
                shift; shift # drop --user <id>
                op=$1; ns=$2; key=$3
                if [ "${'$'}op" = "get" ] && [ "${'$'}key" = "enabled_accessibility_services" ]; then
                  if [ "${'$'}FAIL_READ" = "1" ]; then exit 1; fi
                  if [ -f "${stateFile.absolutePath}" ]; then cat "${stateFile.absolutePath}"; else echo null; fi
                  exit 0
                fi
                if [ "${'$'}op" = "put" ] && [ "${'$'}key" = "enabled_accessibility_services" ]; then
                  if [ "${'$'}FAIL_WRITE" = "1" ]; then exit 1; fi
                  printf '%s' "$4" > "${stateFile.absolutePath}"
                  exit 0
                fi
                if [ "${'$'}op" = "put" ] && [ "${'$'}key" = "accessibility_enabled" ]; then
                  exit 0
                fi
                echo "fake settings: unhandled op ${'$'}op ${'$'}ns ${'$'}key" >&2
                exit 65
                """.trimIndent()
            )
            fakeSettings.setExecutable(true)

            val command = automationOffHookEnableCommand(component, userId)
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment()["PATH"] = dir.absolutePath + ":" + System.getenv("PATH")
            if (failRead) pb.environment()["FAIL_READ"] = "1"
            if (failWrite) pb.environment()["FAIL_WRITE"] = "1"
            pb.redirectErrorStream(false)
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val finalList = if (stateFile.exists()) stateFile.readText() else null
            return ShellResult(exitCode, stdout, stderr, finalList)
        } finally {
            dir.deleteRecursively()
        }
    }

    private data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val finalList: String?,
    )

    @Test
    fun `harness -- empty list adds the component and enables the service`() {
        val result = runEnableCommand(component, userId = 0, initialList = null)
        assertEquals(0, result.exitCode)
        assertEquals(component, result.finalList)
    }

    @Test
    fun `harness -- other apps' entries are preserved, component appended after a colon`() {
        val result = runEnableCommand(
            component,
            userId = 0,
            initialList = "com.tasker/.a11y.Service:com.talkback/.Service",
        )
        assertEquals(0, result.exitCode)
        assertEquals(
            "com.tasker/.a11y.Service:com.talkback/.Service:$component",
            result.finalList,
        )
    }

    @Test
    fun `harness -- already-present component is left untouched (idempotent)`() {
        val existing = "com.tasker/.a11y.Service:$component"
        val result = runEnableCommand(component, userId = 0, initialList = existing)
        assertEquals(0, result.exitCode)
        // No write call for the list happened at all -- file content is unchanged verbatim.
        assertEquals(existing, result.finalList)
    }

    @Test
    fun `harness -- running twice in a row is a no-op the second time`() {
        val dir = createTempDir(prefix = "ramblr-settings-harness-idempotent")
        try {
            val stateFile = java.io.File(dir, "enabled_accessibility_services.txt")
            stateFile.writeText("com.tasker/.a11y.Service")
            val fakeSettings = java.io.File(dir, "settings")
            fakeSettings.writeText(
                """
                #!/bin/sh
                set -e
                shift; shift
                op=$1; key=$3
                if [ "${'$'}op" = "get" ] && [ "${'$'}key" = "enabled_accessibility_services" ]; then
                  cat "${stateFile.absolutePath}"; exit 0
                fi
                if [ "${'$'}op" = "put" ] && [ "${'$'}key" = "enabled_accessibility_services" ]; then
                  printf '%s' "$4" > "${stateFile.absolutePath}"; exit 0
                fi
                exit 0
                """.trimIndent()
            )
            fakeSettings.setExecutable(true)
            val command = automationOffHookEnableCommand(component, userId = 0)
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment()["PATH"] = dir.absolutePath + ":" + System.getenv("PATH")
            pb.start().waitFor()
            val afterFirst = stateFile.readText()
            pb.start().waitFor()
            val afterSecond = stateFile.readText()
            assertEquals(afterFirst, afterSecond)
            assertEquals("com.tasker/.a11y.Service:$component", afterSecond)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `harness -- a failed read aborts with nonzero exit and writes nothing`() {
        val existing = "com.tasker/.a11y.Service"
        val result = runEnableCommand(component, userId = 0, initialList = existing, failRead = true)
        assertNotEquals(0, result.exitCode)
        // File must be exactly what it was before -- the read failure must not fall through to
        // treating the list as empty and clobbering it.
        assertEquals(existing, result.finalList)
    }

    @Test
    fun `harness -- a failed write reports nonzero exit`() {
        val result = runEnableCommand(component, userId = 0, initialList = null, failWrite = true)
        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun `harness -- explicit numeric nonzero user id is passed through to settings`() {
        val dir = createTempDir(prefix = "ramblr-settings-harness-user")
        try {
            val fakeSettings = java.io.File(dir, "settings")
            fakeSettings.writeText(
                """
                #!/bin/sh
                echo "user=${'$'}2" >> "${dir.absolutePath}/calls.log"
                if [ "$3" = "get" ]; then echo null; exit 0; fi
                exit 0
                """.trimIndent()
            )
            fakeSettings.setExecutable(true)
            val command = automationOffHookEnableCommand(component, userId = 10)
            val pb = ProcessBuilder("sh", "-c", command)
            pb.environment()["PATH"] = dir.absolutePath + ":" + System.getenv("PATH")
            pb.start().waitFor()
            val log = java.io.File(dir, "calls.log").readText()
            assertTrue(log.lines().filter { it.isNotBlank() }.all { it == "user=10" })
        } finally {
            dir.deleteRecursively()
        }
    }
}
