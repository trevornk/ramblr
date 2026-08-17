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
