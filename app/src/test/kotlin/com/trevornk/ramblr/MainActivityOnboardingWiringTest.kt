package com.trevornk.ramblr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring regressions for MainActivity's private onboarding callbacks. These assertions deliberately
 * scope source checks to one balanced function body so an unrelated call elsewhere cannot satisfy
 * them (the bug was in callback wiring, not in the pure OnboardingWizard predicate).
 */
class MainActivityOnboardingWiringTest {

    private val source: String by lazy {
        sequenceOf(
            File("src/main/kotlin/com/trevornk/ramblr/MainActivity.kt"),
            File("app/src/main/kotlin/com/trevornk/ramblr/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }

    private fun functionBody(name: String): String {
        val declaration = source.indexOf("private fun $name(")
        require(declaration >= 0) { "Function $name not found" }
        val openingBrace = source.indexOf('{', declaration)
        require(openingBrace >= 0) { "Function $name has no body" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(openingBrace + 1, index)
            }
        }
        error("Function $name has an unbalanced body")
    }

    @Test fun `local onboarding selects recommended model before checking or starting download`() {
        val body = functionBody("showOnboardingModeStep")
        val selection = body.indexOf("selectOnboardingModel(recommended.archive)")
        val installationCheck = body.indexOf("ModelDownloader.isInstalled(this, recommended)")

        assertTrue("recommended model selection must be present", selection >= 0)
        assertTrue("selection must happen before the installed-or-download branch", selection < installationCheck)
    }

    @Test fun `status display and tap action share selected model readiness`() {
        val refresh = functionBody("refresh")
        val statusTap = functionBody("onStatusRowTapped")

        assertTrue(refresh.contains("val hasModel = transcriptionModelReady()"))
        assertTrue(statusTap.contains("hasLocalModel = transcriptionModelReady()"))
        assertFalse(statusTap.contains("LocalTranscriber.availableModels(this).isNotEmpty()"))
    }

    /**
     * #254: opening the app must clear a stale "Ramblr was turned off" notification.
     *
     * WhisperAccessibilityService.onServiceConnected cancels too, but only when the service
     * actually rebinds inside this process. A user whose service came back another way opens the
     * app to fix the notification and would otherwise still see it for up to a worker tick
     * (15 minutes), claiming Ramblr is off while it is demonstrably running. Scoped to refresh()'s
     * balanced body so a cancel call somewhere else in the file cannot satisfy it.
     */
    @Test fun `refresh cancels the recovery notification once the loss is over`() {
        val refresh = functionBody("refresh")
        val healthyBranch = refresh.indexOf("StaleComponentAction.NONE ->")

        assertTrue("refresh must handle the healthy branch", healthyBranch >= 0)
        assertTrue(
            "the healthy branch must cancel the recovery notification, not fall through to Unit",
            refresh.substring(healthyBranch).startsWith(
                "StaleComponentAction.NONE -> ServiceRecoveryNotifications.cancel(this)"
            ),
        )
    }

    @Test fun `a successful silent repair also clears the recovery notification`() {
        val refresh = functionBody("refresh")
        val repaired = refresh.indexOf("toast(\"Ramblr's accessibility service was restored\")")
        val elseBranch = refresh.indexOf("} else if (!InvocationGuardRail.staleBannerDismissed(this))")

        assertTrue("the repair-success branch must exist", repaired >= 0)
        assertTrue("the repair-failure branch must exist", elseBranch > repaired)
        assertTrue(
            "repairing the very condition the notification reports must also cancel it",
            refresh.substring(repaired, elseBranch).contains("ServiceRecoveryNotifications.cancel(this)"),
        )
    }
}
