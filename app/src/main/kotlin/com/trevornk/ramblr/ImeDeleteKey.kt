package com.trevornk.ramblr

import android.icu.text.BreakIterator
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/** The keyboard's delete key: one press, and the hold-to-repeat timing. */
internal object ImeDeleteKey {
    /** Hold this long before the key starts repeating. */
    const val REPEAT_START_MS = 400L
    /** Gap between repeats while deleting characters, and then words. */
    const val REPEAT_CHAR_MS = 60L
    const val REPEAT_WORD_MS = 160L
    /** After this many character repeats (~1s of holding) the key deletes whole words, so a long
     *  dictation can be cleared without holding for ages. */
    const val CHARS_BEFORE_WORDS = 15

    fun wordsAfter(repeats: Int): Boolean = repeats >= CHARS_BEFORE_WORDS

    /**
     * Deletes the selection if there is one, else what lies before the cursor: one character (an
     * emoji or accented letter made of several code points counts as one), or with [word] the
     * word before the cursor plus the spaces after it. With nothing before the cursor it still
     * sends a delete key, so an app can react (merge a list item, remove a chip).
     */
    fun deleteOnce(ic: InputConnection, word: Boolean = false) {
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(if (word) WORD_LOOKBACK else CHAR_LOOKBACK, 0)?.toString()
        if (before.isNullOrEmpty()) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        ic.deleteSurroundingText(before.length - deleteFrom(before, word), 0)
    }

    /** Index in [before] where the deletion starts. */
    internal fun deleteFrom(before: String, word: Boolean): Int {
        if (!word) {
            val chars = BreakIterator.getCharacterInstance().apply { setText(before) }
            return chars.preceding(before.length).takeIf { it != BreakIterator.DONE } ?: 0
        }
        val end = before.trimEnd().length
        if (end == 0) return 0
        val words = BreakIterator.getWordInstance().apply { setText(before) }
        return words.preceding(end).takeIf { it != BreakIterator.DONE } ?: 0
    }

    /** Long enough for the longest emoji sequences (a family emoji is 11 UTF-16 units). */
    private const val CHAR_LOOKBACK = 32
    private const val WORD_LOOKBACK = 64
}
