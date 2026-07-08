package com.trevornk.ramblr

/**
 * The Tier 2 "simple primary choice" a user sees directly under the cleanup toggle (#38): Local
 * (on-device, no key) vs Cloud (their own API key), standing in front of the full waterfall
 * editor now tucked under "Advanced". A waterfall that isn't shaped like either simple choice --
 * more than one step, or a single step of some other group -- is [CUSTOM]: something the user (or
 * a prior version of this app) configured directly, which this simple picker leaves alone rather
 * than force-fitting or overwriting.
 */
enum class SimpleCleanupChoice { LOCAL, CLOUD, CUSTOM }
