package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPreviewTest {

    // --- shouldInjectPartial (throttling) ---

    @Test fun `first partial of a recording injects immediately, bypassing the interval`() {
        assertTrue(shouldInjectPartial("hello", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
    }

    @Test fun `a second partial before the interval elapses is held back`() {
        assertFalse(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1200, minIntervalMs = 400))
    }

    @Test fun `a second partial once the interval elapses is injected`() {
        assertTrue(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1400, minIntervalMs = 400))
    }

    @Test fun `right at the interval boundary is injected`() {
        assertTrue(shouldInjectPartial("hello there", previousText = "hello", lastInjectedAtMs = 1000, nowMs = 1399, minIntervalMs = 399))
    }

    @Test fun `unchanged text never re-injects, even well past the interval`() {
        assertFalse(shouldInjectPartial("hello", previousText = "hello", lastInjectedAtMs = 0, nowMs = 100_000, minIntervalMs = 400))
    }

    @Test fun `blank text never injects, even as the first partial`() {
        assertFalse(shouldInjectPartial("", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
        assertFalse(shouldInjectPartial("   ", previousText = null, lastInjectedAtMs = 0, nowMs = 0, minIntervalMs = 400))
    }

    // --- replacePartialInField (replace-not-append) ---

    @Test fun `replaces the previous partial span with the new text, not appending after it`() {
        // "Hello wrld" -> user said "world", replacing the 4-char "wrld" starting at index 6
        val updated = replacePartialInField(current = "Hello wrld", insertionStart = 6, previousLength = 4, newText = "world")
        assertEquals("Hello world", updated)
    }

    @Test fun `first partial of a session has previousLength zero, so it inserts rather than replaces`() {
        val updated = replacePartialInField(current = "Hello ", insertionStart = 6, previousLength = 0, newText = "world")
        assertEquals("Hello world", updated)
    }

    @Test fun `a shorter revision shrinks the field instead of leaving stray characters`() {
        val updated = replacePartialInField(current = "Hello world today", insertionStart = 6, previousLength = 5, newText = "there")
        assertEquals("Hello there today", updated)
    }

    @Test fun `preserves text before and after the insertion point untouched`() {
        val updated = replacePartialInField(current = "Subject: hello line two", insertionStart = 9, previousLength = 5, newText = "howdy")
        assertEquals("Subject: howdy line two", updated)
    }

    @Test fun `clamps an insertion point past the end of a field that shrank out from under the session`() {
        val updated = replacePartialInField(current = "short", insertionStart = 100, previousLength = 4, newText = "world")
        assertEquals("shortworld", updated)
    }

    @Test fun `clamps a replacement span that would run past the end of the field`() {
        val updated = replacePartialInField(current = "Hello wr", insertionStart = 6, previousLength = 40, newText = "world")
        assertEquals("Hello world", updated)
    }

    // --- reconcileStreamingSpan (final-injection handoff, #45) ---

    @Test fun `streaming session tracking the final injection's own target node has its span replaced with the final text`() {
        // "S awright now..." bug repro: the tracked 1-char leftover "S" must be fully replaced by
        // the final transcript, not left concatenated alongside it.
        val updated = reconcileStreamingSpan(
            current = "S",
            session = StreamingSpan(insertionStart = 0, previousLength = 1),
            finalText = "awright now so this is what it looks like",
            isFinalInjectionTarget = true
        )
        assertEquals("awright now so this is what it looks like", updated)
    }

    @Test fun `streaming session tracking a different node than the final injection has its leftover span cleared, not left orphaned`() {
        val updated = reconcileStreamingSpan(
            current = "Draft: S",
            session = StreamingSpan(insertionStart = 7, previousLength = 1),
            finalText = "irrelevant -- lands elsewhere",
            isFinalInjectionTarget = false
        )
        assertEquals("Draft: ", updated)
    }

    @Test fun `no streaming session at all is completely unaffected`() {
        assertEquals(
            null,
            reconcileStreamingSpan(
                current = "whatever is currently in the field",
                session = null,
                finalText = "final transcript",
                isFinalInjectionTarget = true
            )
        )
    }

    // --- leadingSeparatorFor / withLeadingSeparator (#144) ---

    @Test fun `dictating after an existing draft gets a separating space`() {
        // The exact reported case: "Hello john" + "How old is Tom?" used to glue into
        // "Hello johnHow old is Tom?" on a Pixel 10a WhatsApp composer.
        val current = "Hello john"
        assertEquals(
            "Hello john How old is Tom?",
            current.replaceRange(
                current.length, current.length,
                withLeadingSeparator(current, current.length, "How old is Tom?")
            )
        )
    }

    @Test fun `inserting into an empty field gets no leading space`() {
        assertEquals("", leadingSeparatorFor("", 0, "hello world"))
    }

    @Test fun `inserting at the very start of existing text gets no leading space`() {
        assertEquals("", leadingSeparatorFor("existing", 0, "hello"))
    }

    @Test fun `a draft already ending in a space is not given a second one`() {
        assertEquals("", leadingSeparatorFor("Hello john ", 11, "How old"))
    }

    @Test fun `a draft ending in a newline is already separated`() {
        assertEquals("", leadingSeparatorFor("first line\n", 11, "second line"))
    }

    @Test fun `text that brings its own leading whitespace is not given another separator`() {
        assertEquals("", leadingSeparatorFor("Hello john", 10, " How old"))
    }

    @Test fun `an empty insertion never produces a stray space`() {
        // The streaming-leftover clear path relies on this staying a pure deletion.
        assertEquals("", leadingSeparatorFor("Hello john", 10, ""))
    }

    @Test fun `an open quote or bracket keeps the dictation butted against it`() {
        for (opener in listOf("He said \"", "a list (", "an array [", "a brace {", "Nash-", "path/")) {
            assertEquals(
                "no separator expected after '${opener.last()}'",
                "",
                leadingSeparatorFor(opener, opener.length, "hello")
            )
        }
    }

    @Test fun `a word character before the insertion point gets a separator`() {
        for (current in listOf("word", "ends with digit 7", "ends with period.", "ends with comma,")) {
            assertEquals(
                "separator expected after '${current.last()}'",
                " ",
                leadingSeparatorFor(current, current.length, "hello")
            )
        }
    }

    @Test fun `an insertion point past the end of the field is clamped, not thrown`() {
        assertEquals(" ", leadingSeparatorFor("short", 999, "hello"))
    }

    @Test fun `a caret placed mid-text separates from the character actually before it`() {
        // "Hello john" with caret at index 5 (right after "Hello"): the preceding char is 'o'.
        assertEquals(" ", leadingSeparatorFor("Hello john", 5, "there"))
        // Caret at index 6 (right after the space): already separated.
        assertEquals("", leadingSeparatorFor("Hello john", 6, "there"))
    }

    @Test fun `a streaming session's separator is inside the tracked span, so partials never double it`() {
        // Simulates the real sequence: first partial establishes the span (separator folded in and
        // counted in previousLength), later partials replace that whole span. A separator counted
        // outside the span would accumulate one space per partial.
        val current = "Hello john"
        val start = current.length

        val firstSeparated = withLeadingSeparator(current, start, "How")
        val afterFirst = replacePartialInField(current, start, 0, firstSeparated)
        assertEquals("Hello john How", afterFirst)

        val secondSeparated = withLeadingSeparator(afterFirst, start, "How old")
        val afterSecond = replacePartialInField(afterFirst, start, firstSeparated.length, secondSeparated)
        assertEquals("Hello john How old", afterSecond)

        val thirdSeparated = withLeadingSeparator(afterSecond, start, "How old is Tom")
        val afterThird = replacePartialInField(afterSecond, start, secondSeparated.length, thirdSeparated)
        assertEquals("Hello john How old is Tom", afterThird)
    }

    @Test fun `a shorter streaming revision still keeps exactly one separator`() {
        val current = "draft"
        val start = current.length
        val longSeparated = withLeadingSeparator(current, start, "hello there world")
        val afterLong = replacePartialInField(current, start, 0, longSeparated)
        assertEquals("draft hello there world", afterLong)

        val shortSeparated = withLeadingSeparator(afterLong, start, "hello")
        val afterShort = replacePartialInField(afterLong, start, longSeparated.length, shortSeparated)
        assertEquals("draft hello", afterShort)
    }

    @Test fun `the final injection closing a streaming span keeps the separator`() {
        // #45 handoff + #144: closing the span must not strip the separator back off.
        val current = "Hello john How old"
        val updated = reconcileStreamingSpan(
            current = current,
            session = StreamingSpan(insertionStart = 10, previousLength = 8),
            finalText = "How old is Tom?",
            isFinalInjectionTarget = true
        )
        assertEquals("Hello john How old is Tom?", updated)
    }

    @Test fun `clearing a streaming leftover removes the separator too, leaving the draft untouched`() {
        // The clear path must restore the field exactly, with no orphaned space.
        val updated = reconcileStreamingSpan(
            current = "Hello john How old",
            session = StreamingSpan(insertionStart = 10, previousLength = 8),
            finalText = "",
            isFinalInjectionTarget = false
        )
        assertEquals("Hello john", updated)
    }

    @Test fun `a streaming session starting in an empty field never gains a leading space`() {
        val start = 0
        val firstSeparated = withLeadingSeparator("", start, "How")
        val afterFirst = replacePartialInField("", start, 0, firstSeparated)
        assertEquals("How", afterFirst)

        val secondSeparated = withLeadingSeparator(afterFirst, start, "How old")
        val afterSecond = replacePartialInField(afterFirst, start, firstSeparated.length, secondSeparated)
        assertEquals("How old", afterSecond)
    }

    // --- composeOneShotInjection / composeStreamingPartial (call-site composition, #140 + #144) ---
    //
    // These target the exact composition the service delegates to. Testing only the individual
    // helpers left both service call sites free to silently stop applying them -- mutation testing
    // proved it: reverting either call site kept every helper-level test green.

    @Test fun `one-shot injection into a restored draft appends with a separator`() {
        // The real Pixel 10a case end to end: WhatsApp restored draft reports (0, 0) with the caret
        // visibly at the end. #140 puts the text at the end; #144 separates it.
        assertEquals(
            "Hello john How old is Tom?",
            composeOneShotInjection("Hello john", selStart = 0, selEnd = 0, text = "How old is Tom?")
        )
    }

    @Test fun `one-shot injection into an empty field is just the text`() {
        assertEquals(
            "How old is Tom?",
            composeOneShotInjection("", selStart = -1, selEnd = -1, text = "How old is Tom?")
        )
    }

    @Test fun `one-shot injection with an unreported selection appends to existing text`() {
        assertEquals(
            "draft hello",
            composeOneShotInjection("draft", selStart = -1, selEnd = -1, text = "hello")
        )
    }

    @Test fun `one-shot injection replacing a highlighted range gets no leading space`() {
        // Selecting "world" in "hello world" and dictating over it must replace, not append, and
        // the preceding char is a space so no separator is added either.
        assertEquals(
            "hello there",
            composeOneShotInjection("hello world", selStart = 6, selEnd = 11, text = "there")
        )
    }

    @Test fun `one-shot injection replacing a range mid-text separates from the preceding word`() {
        assertEquals(
            "hello there",
            composeOneShotInjection("helloworld", selStart = 5, selEnd = 10, text = "there")
        )
    }

    @Test fun `streaming first partial folds the separator into the tracked length`() {
        val write = composeStreamingPartial("Hello john", insertionStart = 10, previousLength = 0, displayText = "How")
        assertEquals("Hello john How", write.updatedText)
        // 4, not 3: the separator is inside the span, so the next partial replaces it too.
        assertEquals(4, write.trackedLength)
    }

    @Test fun `streaming partials in a draft never accumulate extra separators`() {
        var current = "Hello john"
        var tracked = 0
        for (partial in listOf("How", "How old", "How old is", "How old is Tom")) {
            val write = composeStreamingPartial(current, insertionStart = 10, previousLength = tracked, displayText = partial)
            current = write.updatedText
            tracked = write.trackedLength
        }
        assertEquals("Hello john How old is Tom", current)
    }

    @Test fun `streaming first partial in an empty field gets no separator`() {
        val write = composeStreamingPartial("", insertionStart = 0, previousLength = 0, displayText = "How")
        assertEquals("How", write.updatedText)
        assertEquals(3, write.trackedLength)
    }

    // --- shouldUseStreamingPreview (streaming-vs-offline gating) ---

    @Test fun `streaming preview requires both the setting and an installed model`() {
        assertTrue(shouldUseStreamingPreview(settingEnabled = true, streamingModelInstalled = true))
    }

    @Test fun `streaming preview is off when the setting is off, even with the model installed`() {
        assertFalse(shouldUseStreamingPreview(settingEnabled = false, streamingModelInstalled = true))
    }

    @Test fun `streaming preview is off when the model isn't installed, even with the setting on`() {
        // Covers the "cleanly disabled" acceptance criterion: a model deleted after being
        // enabled must fall back to no preview, not crash on a missing file.
        assertFalse(shouldUseStreamingPreview(settingEnabled = true, streamingModelInstalled = false))
    }

    @Test fun `streaming preview is off when neither the setting nor the model are present`() {
        assertFalse(shouldUseStreamingPreview(settingEnabled = false, streamingModelInstalled = false))
    }

    // --- shouldRouteStreamingPartialToBubble (live-preview + preview-before-insert interaction fix) ---

    @Test fun `partials are routed to the bubble when Preview-before-insert is on`() {
        // The real field must never be touched pre-commit once Preview-before-insert is enabled,
        // so live partials render in the floating feedback bubble instead of the field.
        assertTrue(shouldRouteStreamingPartialToBubble(previewBeforeInjectEnabled = true))
    }

    @Test fun `partials keep writing straight to the field when Preview-before-insert is off`() {
        // Today's default: no behavior change at all when the toggle is off.
        assertFalse(shouldRouteStreamingPartialToBubble(previewBeforeInjectEnabled = false))
    }

    // --- smartCapitalize (display-only sentence case, #42) ---

    @Test fun `empty string stays empty`() {
        assertEquals("", smartCapitalize(""))
    }

    @Test fun `single sentence gets only its first letter capitalized`() {
        assertEquals("Hello world", smartCapitalize("HELLO WORLD"))
    }

    @Test fun `multiple sentences are capitalized after period, exclamation and question mark`() {
        assertEquals(
            "Hello world. How are you! Fine then? Great",
            smartCapitalize("HELLO WORLD. HOW ARE YOU! FINE THEN? GREAT")
        )
    }

    @Test fun `all-caps input produces correctly cased output throughout, not just the first word`() {
        assertEquals("This is a longer sentence with many words", smartCapitalize("THIS IS A LONGER SENTENCE WITH MANY WORDS"))
    }

    @Test fun `a trailing partial word with no sentence terminator is left as a normal lowercase continuation`() {
        // Streaming hypotheses are incomplete -- the last word may be a mid-utterance fragment.
        assertEquals("Hello world this is a tes", smartCapitalize("HELLO WORLD THIS IS A TES"))
    }

    // --- resolveRealText (hint-text-is-not-content, #47) ---
    //
    // The #47 cases below predate the #140 selection signals, so they pin a plain focused field
    // holding a real cursor -- the state in which only isShowingHintText decides the outcome.

    /** A focused editable field with a real cursor: the #47 state, before #140's signals apply. */
    private fun realText(rawText: String?, isShowingHintText: Boolean): String = resolveRealText(
        rawText = rawText,
        isShowingHintText = isShowingHintText,
        selectionStart = 0,
        selectionEnd = 0,
        isEditable = true,
        isFocused = true,
    )

    @Test fun `hint text showing is treated as empty, not the hint string itself`() {
        assertEquals("", realText(rawText = "RCS message", isShowingHintText = true))
    }

    @Test fun `real typed text is returned unchanged when hint isn't showing`() {
        assertEquals("already typed", realText(rawText = "already typed", isShowingHintText = false))
    }

    @Test fun `null text with hint not showing resolves to empty`() {
        assertEquals("", realText(rawText = null, isShowingHintText = false))
    }

    // --- resolveRealText: self-drawn placeholders (#140) ---
    // WhatsApp's "Message" composer reports isShowingHintText = false AND hintText = null while
    // getText() still returns "Message", so neither the #47 check nor any hint comparison can see
    // it. The distinguishing signal measured on-device is the text selection: a placeholder has no
    // cursor inside it and reports -1/-1, while a field holding real content always reports an
    // insertion point (>= 0). Every constant below is a value observed on a Pixel 10a.

    @Test fun `WhatsApp's self-drawn placeholder with no selection is treated as empty`() {
        // Measured: text='Message' hint=null showingHint=false selStart=-1 selEnd=-1
        assertEquals(
            "",
            resolveRealText(
                rawText = "Message",
                isShowingHintText = false,
                selectionStart = -1,
                selectionEnd = -1,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `the same string typed by the user is kept, because it reports a real cursor`() {
        // Measured: user typed "Message" -> selStart=7 selEnd=7. Identical text to the placeholder
        // above, so selection is the only thing separating a real draft from a placeholder.
        assertEquals(
            "Message",
            resolveRealText(
                rawText = "Message",
                isShowingHintText = false,
                selectionStart = 7,
                selectionEnd = 7,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `a restored WhatsApp draft is never treated as a placeholder`() {
        // Measured: navigate away from a chat with an unsent draft and return -> selStart=0.
        // This is the case that would silently destroy a user's unsent message if 0 were
        // treated the same as -1.
        assertEquals(
            "DraftMyDraft",
            resolveRealText(
                rawText = "DraftMyDraft",
                isShowingHintText = false,
                selectionStart = 0,
                selectionEnd = 0,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `prefilled text that never received a selection event is kept`() {
        // Measured in Chrome: <input value="RealPrefilledText"> focused programmatically and never
        // touched -- the exact "selection not yet reported" hazard resolveInsertionStart documents.
        // It reports 0, not -1, so it must survive.
        assertEquals(
            "RealPrefilledText",
            resolveRealText(
                rawText = "RealPrefilledText",
                isShowingHintText = false,
                selectionStart = 0,
                selectionEnd = 0,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `an unfocused node holding real text is never blanked`() {
        // Measured: Keep note title/body nodes are collected as candidates while unfocused and
        // hold genuine content. The focus guard keeps this path away from them entirely.
        assertEquals(
            "Mushrooms",
            resolveRealText(
                rawText = "Mushrooms",
                isShowingHintText = false,
                selectionStart = -1,
                selectionEnd = -1,
                isEditable = true,
                isFocused = false,
            ),
        )
    }

    @Test fun `a non-editable node holding real text is never blanked`() {
        assertEquals(
            "static label",
            resolveRealText(
                rawText = "static label",
                isShowingHintText = false,
                selectionStart = -1,
                selectionEnd = -1,
                isEditable = false,
                isFocused = true,
            ),
        )
    }

    @Test fun `Keep's search placeholder is still caught by the framework hint flag`() {
        // Measured: Keep reports isShowingHintText=true, so #47's original check already covered
        // it. This is why the earlier text==hint comparison was redundant.
        assertEquals(
            "",
            resolveRealText(
                rawText = "Search Keep",
                isShowingHintText = true,
                selectionStart = -1,
                selectionEnd = -1,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `blank text is left untouched rather than treated as a placeholder`() {
        // The placeholder branch deliberately requires non-blank text: a whitespace-only field is
        // not a placeholder (no app draws one as spaces), and silently trimming the user's
        // whitespace is not this function's job. It falls through and is returned verbatim.
        assertEquals(
            "   ",
            resolveRealText(
                rawText = "   ",
                isShowingHintText = false,
                selectionStart = -1,
                selectionEnd = -1,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `a partially reported selection is not treated as a placeholder`() {
        // Only a fully absent selection (-1/-1) is a placeholder signal; a half-reported
        // selection is ambiguous and must not blank real content.
        assertEquals(
            "real text",
            resolveRealText(
                rawText = "real text",
                isShowingHintText = false,
                selectionStart = -1,
                selectionEnd = 3,
                isEditable = true,
                isFocused = true,
            ),
        )
    }

    @Test fun `isShowingHintText still wins regardless of selection`() {
        // The #47 framework-hint check must short-circuit before the #140 signals are consulted,
        // in every selection state -- otherwise a hint reporting a real cursor would leak through.
        for (selection in listOf(-1, 0, 4)) {
            assertEquals(
                "",
                resolveRealText(
                    rawText = "RCS message",
                    isShowingHintText = true,
                    selectionStart = selection,
                    selectionEnd = selection,
                    isEditable = true,
                    isFocused = true,
                ),
            )
        }
    }

    // --- resolveInsertionStart (first-partial insertion point, #42) ---

    @Test fun `negative selection falls back to the end of the existing text`() {
        assertEquals(12, resolveInsertionStart(selStart = -1, selEnd = -1, currentTextLength = 12))
    }

    @Test fun `a genuine non-zero selection is trusted as intentional cursor placement`() {
        assertEquals(3, resolveInsertionStart(selStart = 3, selEnd = 7, currentTextLength = 20))
    }

    @Test fun `an ambiguous (0, 0) report against non-empty existing text falls back to the end`() {
        // Many EditText/keyboard implementations report a stale (0, 0) selection before any real
        // selection-changed event has fired for the field -- not a genuine cursor-at-start.
        assertEquals(9, resolveInsertionStart(selStart = 0, selEnd = 0, currentTextLength = 9))
    }

    @Test fun `a genuine (0, 0) report against empty existing text is trusted`() {
        assertEquals(0, resolveInsertionStart(selStart = 0, selEnd = 0, currentTextLength = 0))
    }

    @Test fun `a restored WhatsApp draft appends at the end rather than prepending (#140)`() {
        // Regression guard for the direct ACTION_SET_TEXT path in tryInjectIntoNode, which used to
        // trust node.textSelectionStart directly instead of routing through this helper. Measured
        // on-device: leaving a WhatsApp chat with an unsent draft and returning reports selStart=0
        // while the visible cursor sits at the end, so dictating "draft" + " world" produced
        // " worlddraft". Both paths now agree and insert at the end.
        assertEquals(5, resolveInsertionStart(selStart = 0, selEnd = 0, currentTextLength = 5))
    }

    // --- resolveReplacementSpan (one-shot ACTION_SET_TEXT overwrite span, #140) ---

    /** Applies the span the way tryInjectIntoNode does, so the tests assert real user-visible text. */
    private fun inject(current: String, selStart: Int, selEnd: Int, spoken: String): String {
        val span = resolveReplacementSpan(selStart, selEnd, current.length)
        return current.replaceRange(span.start, span.endExclusive, spoken)
    }

    @Test fun `a restored draft reporting (0, 0) is appended to, not prepended (#140)`() {
        // The exact on-device failure: WhatsApp draft restored by leaving and re-entering the chat.
        assertEquals("draft world", inject("draft", selStart = 0, selEnd = 0, spoken = " world"))
    }

    @Test fun `an unreported selection appends at the end`() {
        assertEquals("hello world", inject("hello", selStart = -1, selEnd = -1, spoken = " world"))
    }

    @Test fun `a genuine caret mid-text inserts exactly there`() {
        assertEquals("hel-X-lo", inject("hel-lo", selStart = 4, selEnd = 4, spoken = "X-"))
    }

    @Test fun `a genuine ranged selection replaces the highlighted text`() {
        // Dictating over a highlighted word must still overwrite it rather than insert alongside.
        assertEquals("hello there", inject("hello world", selStart = 6, selEnd = 11, spoken = "there"))
    }

    @Test fun `a reversed ranged selection is normalised rather than crashing`() {
        // Some nodes report the anchor after the focus when the user drags right-to-left.
        assertEquals("hello there", inject("hello world", selStart = 11, selEnd = 6, spoken = "there"))
    }

    @Test fun `a genuine (0, 0) caret in an empty field is trusted`() {
        assertEquals("world", inject("", selStart = 0, selEnd = 0, spoken = "world"))
    }

    @Test fun `a stale index past the end of the text is clamped instead of throwing`() {
        // A node can report a selection against a string it is no longer showing; replaceRange
        // would throw IndexOutOfBoundsException uncaught and kill the accessibility service.
        assertEquals("hi world", inject("hi", selStart = 99, selEnd = 99, spoken = " world"))
    }

    @Test fun `a partially reported selection is treated as unreliable and appends`() {
        assertEquals("hello world", inject("hello", selStart = 0, selEnd = -1, spoken = " world"))
    }

    @Test fun `an overridden start never deletes existing text`() {
        // start is overridden to the end as unreliable; the span must collapse to a pure insertion
        // rather than deleting through to a selEnd derived from the same distrusted report.
        val span = resolveReplacementSpan(selStart = 0, selEnd = 0, currentTextLength = 5)
        assertEquals(span.start, span.endExclusive)
    }
}
