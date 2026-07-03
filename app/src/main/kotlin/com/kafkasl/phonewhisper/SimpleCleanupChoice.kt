package com.kafkasl.phonewhisper

/**
 * The Tier 2 "simple primary choice" a user sees directly under the cleanup toggle (#38): Local
 * (on-device, no key) vs Cloud (their own API key), standing in front of the full waterfall
 * editor now tucked under "Advanced". A waterfall that isn't shaped like either simple choice --
 * more than one step, or a single step of some other group -- is [CUSTOM]: something the user (or
 * a prior version of this app) configured directly, which this simple picker leaves alone rather
 * than force-fitting or overwriting.
 */
enum class SimpleCleanupChoice { LOCAL, CLOUD, CUSTOM }

/**
 * Pure classification of [waterfall] (as persisted by [CleanupWaterfallStore], i.e. already
 * defaulting to [CleanupWaterfall.LEGACY_SINGLE_STEP] when nothing was ever saved) into one of the
 * two simple choices, or [SimpleCleanupChoice.CUSTOM] if it doesn't match either shape. A fresh
 * install's implicit default (a single LEGACY step) reads as [SimpleCleanupChoice.CLOUD] -- the
 * same behavior it already has today -- and a single LOCAL_LLM step (see #37) reads as
 * [SimpleCleanupChoice.LOCAL]. Anything else (multiple steps, or one step of a different group,
 * e.g. a hand-configured OmniRoute/Direct-provider step) is left as [SimpleCleanupChoice.CUSTOM]
 * so the simple picker never silently misrepresents -- or lets a stray tap overwrite -- a
 * power-user's real Advanced configuration.
 */
fun simpleCleanupChoiceFor(waterfall: CleanupWaterfall): SimpleCleanupChoice {
    val steps = waterfall.steps
    if (steps.size != 1) return SimpleCleanupChoice.CUSTOM
    return when (steps[0].group) {
        CleanupStepGroup.LOCAL_LLM -> SimpleCleanupChoice.LOCAL
        CleanupStepGroup.LEGACY -> SimpleCleanupChoice.CLOUD
        else -> SimpleCleanupChoice.CUSTOM
    }
}
