package com.trevornk.ramblr

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

/**
 * The license-consent dialog that fronts [ModelDownloadWorker.enqueue] for non-free models
 * (F-Droid inclusion review, fdroiddata!42401).
 *
 * Split from [ModelLicenseConsent] on purpose: that object holds the *policy* (has this been
 * accepted? may this be downloaded?) and stays pure/Context-only so it's unit-testable, while
 * this file holds the Activity/dialog plumbing that can't be. Call sites use this one function
 * instead of hand-rolling a dialog each time, so the wording, the "View license" affordance, and
 * the accept-then-enqueue ordering can't drift between the cleanup picker and onboarding.
 */

/**
 * Downloads [model], first obtaining explicit license consent if the model needs it.
 *
 * For a freely-licensed model (everything in the catalog except LFM2.5-350M) this is just
 * `enqueue` with no extra UI. For a non-free model it shows the license name, the concrete
 * restriction, and a link to the terms, and enqueues only on explicit acceptance.
 *
 * Consent is recorded *before* enqueueing rather than after, because [ModelDownloadWorker.enqueue]
 * itself re-checks consent at the chokepoint -- recording afterwards would make the guard reject
 * the very download the user just approved.
 *
 * @param onStarted invoked only when a download was actually enqueued, so callers can show
 *   "Downloading..." without claiming a download that the user declined.
 * @param onDeclined invoked exactly when [onStarted] is not: the prompt was cancelled, dismissed,
 *   or the enqueue did not happen. Callers that gate further UI on the answer (onboarding, #170)
 *   need a signal for "the user is done with this dialog and said no", otherwise a wizard waiting
 *   on consent would stall forever on a dismissed prompt. Exactly one of the two always runs.
 *   Note that "View license" dismisses the dialog to hand off to a browser, so it reports declined
 *   too -- the user can re-enter the choice afterwards rather than being held in a modal.
 */
fun Activity.downloadModelWithLicenseConsent(
    model: Model,
    onStarted: () -> Unit = {},
    onDeclined: () -> Unit = {},
) {
    if (ModelLicenseConsent.canDownload(this, model)) {
        if (ModelDownloadWorker.enqueue(this, model)) onStarted() else onDeclined()
        return
    }
    // Tracked across the whole dialog rather than per-button so that back, outside-tap, "Cancel"
    // and a failed enqueue all funnel into the same single onDeclined -- and so acceptance never
    // double-reports through the dismiss listener that follows it.
    var started = false
    AlertDialog.Builder(this)
        .setTitle("${model.name} uses a non-free license")
        .setMessage(ModelLicenseConsent.consentMessage(model))
        .setPositiveButton("Accept and download") { _, _ ->
            ModelLicenseConsent.recordAccepted(this, model)
            if (ModelDownloadWorker.enqueue(this, model)) {
                started = true
                onStarted()
            }
        }
        .setNegativeButton("Cancel", null)
        .setNeutralButton("View license") { _, _ ->
            // Deliberately hands off to a browser rather than an in-app WebView: F-Droid's review
            // checklist flags unnecessary in-app webviews, and this is a one-off external link.
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.license.url)))
            }
        }
        .setOnDismissListener { if (!started) onDeclined() }
        .show()
}
