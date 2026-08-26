#!/usr/bin/env python3
"""Generates app/src/main/res/raw/suggestion_filter_words.txt (issue #216).

The file is the SUGGESTION-FILTER dictionary for smart vocabulary suggestions: a list of
common English words that must never be suggested as personal-vocabulary terms. It is NOT
used by VocabularyPostCorrector (whose guard list is CommonEnglishWords.kt) -- it only
suppresses suggestion candidates, so the requirements are:

  - big enough that ordinary-but-uncommon words ("sheriff", "governor", "soliloquy" -- the
    junk classes measured in the #216 spike) are covered, and
  - free of proper nouns, because names ("Hetzner", "Elsinore", "Dumas") are exactly what
    the feature exists to surface.

Source: wordfreq's top-60k English list (https://pypi.org/project/wordfreq/, MIT-ish
licensed data), filtered to pure a-z entries, then cross-checked against
/usr/share/dict/words (macOS, web2) to drop proper-noun-shaped entries: a word is dropped
when the system dictionary lists it ONLY with an initial capital (John, French, Hamlet...).
Words the system dictionary doesn't know at all are kept -- being in wordfreq's top-60k is
itself strong evidence of common usage (internet-era vocabulary the 1934 web2 list lacks).

Reproduce with:
    python3 -m venv /tmp/wf-venv && /tmp/wf-venv/bin/pip install wordfreq
    /tmp/wf-venv/bin/python tools/generate_suggestion_filter_words.py

Output is one lowercase word per line, sorted, deterministic for a given wordfreq version.
"""

import re
from pathlib import Path

from wordfreq import top_n_list

TOP_N = 60_000
OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/raw/suggestion_filter_words.txt"

ALPHA = re.compile(r"^[a-z]+$")


def main() -> None:
    dict_words = Path("/usr/share/dict/words").read_text().split("\n")
    lower_listed = {w for w in dict_words if w and w[0].islower()}
    cap_listed = {w.lower() for w in dict_words if w and w[0].isupper()}
    # Proper-noun-shaped: the system dictionary knows the word ONLY capitalized.
    proper_only = cap_listed - lower_listed

    kept = []
    for word in top_n_list("en", TOP_N):
        if not ALPHA.match(word):
            continue  # numbers, apostrophes, hyphens, non-ascii
        if len(word) < 2:
            continue
        if word in proper_only:
            continue
        kept.append(word)

    kept = sorted(set(kept))
    OUT.write_text("\n".join(kept) + "\n")
    size = OUT.stat().st_size
    print(f"wrote {len(kept)} words, {size} bytes -> {OUT}")
    for expected in ("the", "house", "color", "colour", "sheriff", "governor"):
        assert expected in set(kept), expected
    for absent in ("hetzner", "elsinore", "margolotte"):
        assert absent not in set(kept), absent


if __name__ == "__main__":
    main()
