package com.trevornk.ramblr

/**
 * The Tier 2 "simple primary choice" a user sees directly under the cleanup toggle (#38): Local
 * (on-device, no key) vs Cloud (their own API key), standing in front of the full waterfall editor
 * now tucked under "Advanced". Cleanup routing is driven by [CloudFeatureToggle.cleanupEnabled], so
 * only these two states are ever displayed or acted on.
 */
enum class SimpleCleanupChoice { LOCAL, CLOUD }
