package com.trevornk.ramblr

/**
 * How long ONE chunk of a chunked on-device cleanup may run (#325 follow-up), derived from how
 * fast this device has actually been decoding rather than a constant picked on one phone.
 *
 * ## Why a constant failed
 *
 * #327 gave every chunk a flat 20 s. Measured on a Pixel 10a (Tensor G4) with the 300-character
 * chunks [LocalCleanupChunker] cuts: LFM2.5-350M takes a median 7.5 s (max 11.5 s) and fits;
 * `mumble-cleanup-2stage` -- a Qwen2.5-0.5B fine-tune, i.e. ~1.4x the parameters and a 151k-entry
 * output vocabulary, NOT a model that runs two passes: "2-stage" is how it was trained -- takes
 * a median 10-14 s per chunk with cleaned chunks up to 18.3 s, so 3 of 40 and 3 of 35 chunks
 * crossed 20 s on a *fast* phone. The reporter's Galaxy S20+ (Snapdragon 865 / Exynos 990) is
 * several times slower again, so a flat budget there would time out every chunk. Decode time is
 * proportional to the number of tokens generated, and cleanup emits about as many characters as
 * it is given, so a chunk's duration is well modelled as `msPerChar * chunkChars`; what differs
 * between models and phones is `msPerChar`, and that is directly observable after the first
 * chunk.
 *
 * ## The rule
 *
 *  - **Estimate**: the median ms-per-input-character of the last [WINDOW] chunks that actually
 *    decoded. The median (not the mean, not the max) keeps one stalled chunk -- thermal throttle,
 *    a GC pause, another app stealing the big cores -- from inflating every later budget, or a
 *    single lucky short output from deflating them.
 *  - **Budget** = [FACTOR] x estimate x the chunk's own length, clamped to
 *    [[MIN_BUDGET_MS], [MAX_BUDGET_MS]]. 2.5x covers the observed spread (slowest completed chunk
 *    1.4x the median on the 10a, one chunk that needed >1.5x) with room, yet a decode that is
 *    truly stuck still costs only 2.5 typical chunks, not minutes.
 *  - **No data yet** (the first chunk): [INITIAL_BUDGET_MS]. Deliberately roomy -- it must clear a
 *    phone ~4x slower than the 10a on its first chunk, because the alternative is a timeout
 *    before there is anything to adapt from.
 *  - **A timeout counts as evidence, not noise**: the chunk is recorded at [TIMEOUT_INFLATION]x
 *    the time it was given (it ran at least that long). In the first chunks of a slow phone that
 *    drags the median up so the next budget really is larger; once the window is full of healthy
 *    samples a single timeout is an outlier and the median ignores it.
 *  - **Floor** [MIN_BUDGET_MS] is the 20 s #327 shipped, so a device that already worked never
 *    gets a *tighter* budget than before; only slower ones get more.
 *
 * The executor also asks [expectedMs] before starting a chunk and delivers the remainder raw
 * rather than start one that the step's remaining time cannot hold (see
 * [CleanupWaterfallExecutor]'s chunked branch), so a slow phone ends with an honest partial
 * result instead of a chunk killed at the step deadline.
 *
 * Pure: no clock, no Android; the executor feeds it durations.
 */
class LocalCleanupChunkBudget {
    private val samples = ArrayDeque<Double>()

    /** Median ms per input character over the recent window, or null before any evidence. */
    fun estimateMsPerChar(): Double? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Wall-clock allowance for a chunk of [chunkChars] characters. */
    fun budgetMs(chunkChars: Int): Long {
        val perChar = estimateMsPerChar() ?: return INITIAL_BUDGET_MS
        return (FACTOR * perChar * chunkChars).toLong().coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)
    }

    /** Typical (not worst-case) duration of a chunk of [chunkChars], or null before any evidence. */
    fun expectedMs(chunkChars: Int): Long? =
        estimateMsPerChar()?.let { (it * chunkChars).toLong() }

    /** Records a chunk that decoded to completion in [tookMs]. */
    fun recordCompleted(chunkChars: Int, tookMs: Long) = add(chunkChars, tookMs.toDouble())

    /** Records a chunk that ran out of its [allowedMs] allowance. */
    fun recordTimedOut(chunkChars: Int, allowedMs: Long) = add(chunkChars, allowedMs * TIMEOUT_INFLATION)

    private fun add(chunkChars: Int, ms: Double) {
        if (chunkChars <= 0) return
        samples.addLast(ms / chunkChars)
        while (samples.size > WINDOW) samples.removeFirst()
    }

    companion object {
        /** Budget multiple of the typical chunk duration; see the class kdoc. */
        const val FACTOR = 2.5

        /** Chunks the median looks back over. */
        const val WINDOW = 5

        /** What a timed-out chunk is recorded as, relative to the time it was given. */
        const val TIMEOUT_INFLATION = 1.5

        /** Never tighter than the flat budget #327 shipped. */
        const val MIN_BUDGET_MS = 20_000L

        /** Hard ceiling on one chunk; a decode this long is stuck on any phone worth supporting. */
        const val MAX_BUDGET_MS = 120_000L

        /** First chunk, before there is a measurement. */
        const val INITIAL_BUDGET_MS = 60_000L
    }
}
