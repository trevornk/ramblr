# Eval sample notes

Not read by `EvalHarness.kt` (only `*.txt` files in this directory are loaded as samples) — this
is a human-reference companion for judging report output. For each sample: what a *good* cleanup
should preserve, and what it should fix. See issue #2.

## quick_note (short, low-effort dictation — should need minimal restructuring)

- **quick_note_01**: Fix capitalization only. One idea, one sentence — must not be split into a list.
- **quick_note_02**: Fix capitalization/punctuation. "it should be ready by five" is a distinct clause — keep it attached, don't drop it as filler.
- **quick_note_03**: Preserve the name "Sarah" (capitalize) and the specific time comparison ("seven works better than six") — don't collapse to just "dinner Saturday."
- **quick_note_04**: A genuine question. Must be returned as a cleaned-up question ("Where is Paris?"), never answered.

## rambling_brainstorm (long run-on monologue — biggest test of restructuring)

- **rambling_brainstorm_01**: Two distinct, only loosely related ideas (onboarding permissions screen; local-mode-first setup) sharing one run-on sentence — must survive as two separated points, not merged into one or silently dropped. The closing "let me not go down that path" meta-comment is disfluent scaffolding, safe to drop, but the idea before it is not.
- **rambling_brainstorm_02**: Two ideas ("also unrelated but...") — the button/left-hand idea and the keyboard-overlap idea — both must appear; the second is explicitly flagged as unrelated and shorter, easy to accidentally cut as an afterthought.
- **rambling_brainstorm_03**: Three distinct suggestions (resumable downloads, ETA vs. percentage, queued overnight downloads) plus an explicit priority ranking ("that's probably a lower priority") — the ranking is real content, not filler, and must survive.
- **rambling_brainstorm_04**: Single continuous idea (personal vocabulary learning) — should stay as a paragraph, not be forced into a list just because it's long.
- **rambling_brainstorm_05**: The speaker goes back and forth (toast vs. silent) before landing on a final preference ("I think that's the better approach honestly") — output should reflect the final preference clearly, not present both options as equally undecided.

## self_correction (explicit correction/retraction language — the sharpest test of #1's rule 2)

- **self_correction_01**: Two nested corrections (friday -> thursday -> "as soon as you finish reviewing"). Only the final instruction should remain; must not retain "thursday" anywhere in the output.
- **self_correction_02**: Correction plus meta-commentary ("I'm looking at the wrong calendar") — the meta-commentary explaining *why* should be dropped silently, but the substantive correction (which floor / which room) must be kept.
- **self_correction_03**: Correction reverses the *order* of two steps (deploy vs. test), not just a fact — a naive filler-stripper could get the sequencing backwards. Also has a real conditional ("only deploy if tests pass") that must survive.
- **self_correction_04**: Correction plus reasoning for both the old and new price *and* a forward-looking caveat about raising prices later — all three are real content (final price, reason for the increase, reason not to overshoot) and must all be kept, not just the final number.

## spoken_list (explicit enumeration — tests list/bullet formatting)

- **spoken_list_01**: Five sequential release steps ("number one" ... "number five") — must become a numbered list, in the same order, with nothing merged or dropped.
- **spoken_list_02**: Numbered repro steps ending in a plain-prose bug description — the bug description after the steps is not itself a list item and should stay as trailing prose, not get force-numbered as a fifth step.
- **spoken_list_03**: An unordered grocery list with an embedded exception ("if they have it maybe grab...") — should be a bullet list, and the conditional nuance on the basil item should not be flattened away to look as certain as the other items.
- **spoken_list_04**: Four pending checklist items introduced with ordinal words — must become a numbered or bulleted list; "still pending" framing from the opening line is real status information worth keeping in a lead-in line, not just discarded.

## technical_jargon (mis-heard project/technical terms — tests the vocabulary term list, not restructuring)

- **technical_jargon_01**: Contains four separate mis-hearings of known terms ("n b dev" -> nbdev, "fast core" -> fastcore, "fast html" -> FastHTML, "solve it" -> Solveit, "answer dot ai" -> Answer.AI) — every one must be corrected; none should be "cleaned" into a *different* plausible-sounding word instead of the actual project name.
- **technical_jargon_02**: "hetzsner" -> Hetzner, "pi" -> Pi (capitalized project name, easy to accidentally lowercase as the pronoun). Must preserve the causal chain (daemon down -> health check failing -> no alert) rather than summarizing it away.
- **technical_jargon_03**: "clawed code" -> Claude Code, "codecks" -> Codex — a comparison between the two tools; must not flip which tool is being praised vs. criticized.
- **technical_jargon_04**: "fast dot ai" -> fast.ai, "n b dev" -> nbdev, "solve it" -> Solveit — tests that jargon correction survives even when the sentence is otherwise already fairly clean/well-formed.

## edge cases

- **edge_very_short_01**: Two-word command. Should stay a two-word (or near-identical) sentence — a regression where the model pads a trivial instruction into a full paragraph or invents unstated detail (e.g. a time, a reason) would show up clearly here.
- **edge_already_clean_01**: Already grammatically correct, capitalized, and punctuated. A good cleanup pass is a no-op or near no-op — any prompt variant that meaningfully rewords or restructures this one is being overly aggressive.
