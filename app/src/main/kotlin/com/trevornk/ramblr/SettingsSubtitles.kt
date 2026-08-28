package com.trevornk.ramblr

/**
 * Pure formatting for a cleanup-model row's subtitle, and the flavor gate for the Advanced
 * screen's subtitle.
 *
 * Both exist as free functions with no Android dependency so they can be unit-tested. The bugs
 * they encode (#153, from F-Droid review fdroiddata!42401) were invisible in unit tests precisely
 * because the logic lived inside Activity code that the JVM suite can't construct:
 *
 *  - the license note was appended when the row was built and then silently overwritten by the
 *    refresh that ran immediately afterwards, so a user comparing cleanup models never saw that
 *    one of them isn't FOSS;
 *  - the Advanced subtitle hardcoded "updates" even on the storefront/F-Droid build, which
 *    compiles in no self-updater at all.
 */

/**
 * The subtitle shown under a cleanup model's name, e.g.
 * `"Good · 352 MB"` or `"Best · 250 MB · LFM Open License v1.0 (not FOSS)"`.
 *
 * There is exactly one of these so the row builder and the row refresh can't disagree; when they
 * did, whichever ran last won and the license note vanished.
 *
 * @param installed whether the model's files are already on disk.
 * @param sideloadOnly whether the model has no download URL (#H7) and must be pushed manually.
 */
fun cleanupModelSubtitleText(
    model: Model,
    installed: Boolean,
    sideloadOnly: Boolean,
): String {
    val licenseNote = if (model.license.isFree) "" else " · ${model.license.name} (not FOSS)"
    // Only meaningful before the file exists: once installed, the row is usable like any other.
    val sideloadNote = if (!installed && sideloadOnly) " · sideload only" else ""
    return "${model.quality} · ${model.sizeMb} MB$licenseNote$sideloadNote"
}

/**
 * The Advanced category subtitle. [hasSelfUpdate] must reflect whether the self-updater is
 * compiled into this flavor -- the github flavor has it, storefront/F-Droid does not, and
 * advertising an Updates screen that isn't there was flagged in F-Droid review.
 */
fun advancedSubtitleText(hasSelfUpdate: Boolean): String {
    val updates = if (hasSelfUpdate) "updates, " else ""
    return "Redo setup, overlay appearance, behavior, ${updates}data & logs"
}

/**
 * The main settings screen's "Personal vocabulary" row subtitle (#217): a live term count plus a
 * one-line pitch, e.g. `"12 terms — names and jargon the models should get right"`. The count is
 * the discoverability hook -- #140's reporter looked for exactly this feature and couldn't find
 * it, so the row leads with evidence that it exists and is populated.
 */
fun vocabularyMainRowSubtitleText(termCount: Int): String {
    val count = when (termCount) {
        0 -> "No terms yet"
        1 -> "1 term"
        else -> "$termCount terms"
    }
    return "$count — names and jargon the models should get right"
}

/**
 * The Cloud screen's experimental "Live cloud transcription" row subtitle (#233 Phase 1).
 *
 * Mirrors [CloudLiveWiring.isLiveAllowed]'s three conditions so the row can never claim the
 * feature is running when the wiring would in fact hand back null. When the switch is on but a
 * precondition is missing, the subtitle names the specific missing piece rather than a generic
 * "not configured" -- the toggle looks satisfied at that point, so the row is the only place the
 * user can find out why nothing changed.
 *
 * Pure (no Context) so the on/blocked/off wording is unit-testable, like the rest of this file.
 */
fun cloudLiveSubtitleText(
    enabled: Boolean,
    useLocalTranscription: Boolean,
    hasGeminiKey: Boolean,
): String = when {
    !enabled -> "Off — cloud transcription returns your text once you stop speaking"
    useLocalTranscription -> "On, but transcription is set to on-device — needs cloud transcription above"
    !hasGeminiKey -> "On, but no Gemini key is set — add a Gemini provider above"
    else -> "On — the keyboard streams to Gemini as you speak. Experimental, costs more than batch"
}

/**
 * The Behavior screen's "Personal vocabulary" row subtitle: the term list itself, prefixed with
 * the #185 inert-setting warning when nothing in the current configuration applies the terms.
 * Extracted from BehaviorActivity's private `vocabularySummary` (#217) so it's unit-testable and
 * shared through [VocabularyEditor.rowSummary].
 */
fun vocabularyRowSummaryText(terms: List<String>, inert: Boolean): String {
    val termsPart = if (terms.isEmpty()) "No custom terms" else terms.joinToString(", ")
    // #185: the raw term list reads as confirmation the terms are in force -- when nothing
    // in the current setup applies them (local ASR with cleanup fully off, since #182's
    // post-pass made local cleanup vocabulary-capable) they aren't, so say so on the row.
    return if (inert) "Not used while cleanup is off — $termsPart" else termsPart
}
