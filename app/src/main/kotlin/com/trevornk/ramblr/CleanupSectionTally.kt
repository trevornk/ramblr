package com.trevornk.ramblr

/**
 * Adds up how much of an audio job's transcript was actually cleaned, across the several waterfall
 * runs a long transcript is cut into (#325). A "section" is one on-device chunk when the chunked
 * local path served a piece, and the whole piece otherwise (one cloud call). Counts only -- never
 * transcript text.
 */
class CleanupSectionTally {
    var cleaned = 0
        private set
    var total = 0
        private set

    /** A piece that came back cleaned. [sections] is the chunk report of a chunked local run, if any. */
    fun pieceCleaned(sections: PartialCleanup?) {
        if (sections != null) { cleaned += sections.cleanedChunks; total += sections.totalChunks }
        else { cleaned += 1; total += 1 }
    }

    /** A piece delivered raw. [rawSections] is how many sections it would have been cleaned in. */
    fun pieceRaw(rawSections: Int) {
        total += rawSections.coerceAtLeast(1)
    }

    val isNone get() = total == 0
    val allCleaned get() = total > 0 && cleaned == total
    val noneCleaned get() = total > 0 && cleaned == 0
    val partlyCleaned get() = cleaned in 1 until total
}
