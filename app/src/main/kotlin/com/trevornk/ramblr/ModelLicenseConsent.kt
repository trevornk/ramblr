package com.trevornk.ramblr

import android.content.Context

/**
 * One-time, per-model record of the user having been shown a non-free model's license terms and
 * explicitly accepting them (F-Droid inclusion review, fdroiddata!42401).
 *
 * F-Droid's inclusion policy allows an app to download additional binaries only with consent that
 * is opt-in and "clearly explains to users that they're choosing to bypass F-Droid's checks."
 * Ramblr's model downloads are already user-initiated, but a model published under terms more
 * restrictive than the app's own GPLv3 needs more than an implicit "you tapped download": the
 * user has to actually see the license before accepting it.
 *
 * Kept as a pure, Context-only object with no UI so the *policy* ("has this been accepted?") is
 * testable independently of the dialog that collects the acceptance, and so every call site
 * consults the same record rather than each re-deriving when a prompt is needed.
 *
 * Consent is stored per model archive, not as one global flag: accepting LFM2.5's terms says
 * nothing about any other non-free model that might be added later.
 */
object ModelLicenseConsent {

    private const val PREFS = "model_license_consent"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Key is the archive id, which is already this catalog's stable per-model identifier. */
    private fun key(model: Model) = "accepted_${model.archive}"

    /**
     * True when [model] may be downloaded right now without showing a license prompt: either it's
     * freely licensed, or its terms were previously shown and accepted.
     *
     * Deliberately expressed as "may download" rather than "has consented" so the common case
     * (every free model in the catalog) reads as an unconditional yes at the call site instead of
     * requiring each caller to remember the `!requiresLicenseConsent` half of the condition.
     */
    fun canDownload(context: Context, model: Model): Boolean =
        !model.requiresLicenseConsent || hasAccepted(context, model)

    /** Whether the user has explicitly accepted this specific model's license terms. */
    fun hasAccepted(context: Context, model: Model): Boolean =
        prefs(context).getBoolean(key(model), false)

    /** Record acceptance after the user has been shown the license name, URL, and restriction. */
    fun recordAccepted(context: Context, model: Model) {
        prefs(context).edit().putBoolean(key(model), true).apply()
    }

    /**
     * Revoke a previously-recorded acceptance. Used when the user deletes a non-free model: the
     * next download should re-prompt rather than silently reusing consent for a model they took
     * a deliberate action to remove.
     */
    fun clearAccepted(context: Context, model: Model) {
        prefs(context).edit().remove(key(model)).apply()
    }

    /**
     * User-facing explanation shown before download. States the restriction in concrete terms
     * rather than "non-free": a user deciding whether to accept needs to know what the license
     * actually does, and "not FLOSS" alone doesn't tell them.
     */
    fun consentMessage(model: Model): String =
        "\"${model.name}\" is not free/open-source software, unlike Ramblr itself (GPLv3).\n\n" +
            "It is published by its authors under the ${model.license.name}, which places " +
            "restrictions on use that an open-source license would not — notably limiting " +
            "commercial use by larger companies.\n\n" +
            "The model is downloaded from its publisher, not bundled with Ramblr, and is only " +
            "used for optional on-device cleanup. You can use Ramblr fully without it.\n\n" +
            "License terms: ${model.license.url}"
}
