#!/usr/bin/env bash
# Verifies .githooks/* through REAL git commits in a throwaway repo.
# Invoking the scripts directly only proves they exit non-zero; this proves
# git actually fires them and that a bad-author commit cannot land.
set -uo pipefail

HOOKS="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
PASS=0; FAIL=0
ok()   { printf '  ok   %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL %s\n' "$1"; FAIL=$((FAIL+1)); }
check(){ [[ "$2" == "$3" ]] && ok "$1" || bad "$1 (got '$2', want '$3')"; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
git init -q "$TMP"; cd "$TMP"
git config core.hooksPath "$HOOKS"
git config commit.gpgsign false
echo x > f; git add f

GOOD_N="Trevor Nash-Keller"; GOOD_E="4496266+trevornk@users.noreply.github.com"

# commit as a given identity; echo "landed" or "blocked"
try() {
  local n="$1" e="$2" msg="${3:-msg}"
  echo "$RANDOM" > f; git add f
  if GIT_AUTHOR_NAME="$n" GIT_AUTHOR_EMAIL="$e" \
     GIT_COMMITTER_NAME="$n" GIT_COMMITTER_EMAIL="$e" \
     git commit -q -m "$msg" >/dev/null 2>&1; then echo landed; else echo blocked; fi
}

echo "== pre-commit: identities that caused the real incident =="
check "Neo <neo@localhost> blocked"        "$(try 'Neo' 'neo@localhost')"                 blocked
check "Neo <neo@local> blocked"            "$(try 'Neo' 'neo@local')"                     blocked
check "stranger trevor@ blocked"           "$(try 'Trevor' 'trevor@users.noreply.github.com')" blocked
check "company nkm addr blocked"           "$(try 'Trevor NK' 'trevor@nashkellermedia.com')"   blocked
check "wrong name, right email blocked"    "$(try 'trevornk' "$GOOD_E")"                  blocked
check "canonical identity lands"           "$(try "$GOOD_N" "$GOOD_E")"                   landed
check "legacy noreply lands (amends)"      "$(try "$GOOD_N" 'trevornk@users.noreply.github.com')" landed

echo "== pre-commit: split author/committer (worktree leak shape) =="
echo "$RANDOM" > f; git add f
if GIT_AUTHOR_NAME="$GOOD_N" GIT_AUTHOR_EMAIL="$GOOD_E" \
   GIT_COMMITTER_NAME="Neo" GIT_COMMITTER_EMAIL="neo@localhost" \
   git commit -q -m m >/dev/null 2>&1; then bad "bad committer blocked"; else ok "bad committer blocked"; fi

echo "== commit-msg: trailer hygiene =="
body=$'Fix a thing\n\nReal body.\n\nCo-Authored-By: Claude <noreply@anthropic.com>\nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>'
echo "$RANDOM" > f; git add f
GIT_AUTHOR_NAME="$GOOD_N" GIT_AUTHOR_EMAIL="$GOOD_E" GIT_COMMITTER_NAME="$GOOD_N" \
  GIT_COMMITTER_EMAIL="$GOOD_E" git commit -q -m "$body" >/dev/null 2>&1
msg="$(git log -1 --format=%B)"
grep -qi 'claude' <<<"$msg" && bad "AI trailers stripped" || ok "AI trailers stripped"
grep -q 'Real body.' <<<"$msg" && ok "body preserved" || bad "body preserved"
[[ "$(git log -1 --format=%s)" == "Fix a thing" ]] && ok "subject intact" || bad "subject intact"
[[ "$msg" =~ [[:space:]]$ ]] && bad "no trailing blank lines" || ok "no trailing blank lines"

human=$'Fix\n\nCo-authored-by: Trevor Nash-Keller <4496266+trevornk@users.noreply.github.com>'
echo "$RANDOM" > f; git add f
GIT_AUTHOR_NAME="$GOOD_N" GIT_AUTHOR_EMAIL="$GOOD_E" GIT_COMMITTER_NAME="$GOOD_N" \
  GIT_COMMITTER_EMAIL="$GOOD_E" git commit -q -m "$human" >/dev/null 2>&1
grep -q 'Co-authored-by: Trevor' <<<"$(git log -1 --format=%B)" \
  && ok "human co-author preserved" || bad "human co-author preserved"

echo "== escape hatch =="
echo "$RANDOM" > f; git add f
if ATTRIBUTION_GUARD=off GIT_AUTHOR_NAME="Neo" GIT_AUTHOR_EMAIL="neo@localhost" \
   GIT_COMMITTER_NAME="Neo" GIT_COMMITTER_EMAIL="neo@localhost" \
   git commit -q -m m >/dev/null 2>&1; then ok "ATTRIBUTION_GUARD=off bypasses"; else bad "ATTRIBUTION_GUARD=off bypasses"; fi

echo "== no bad author survived in history =="
strays="$(git log --format='%an <%ae>|%cn <%ce>' | grep -cvE "^${GOOD_N} <(4496266\+trevornk|trevornk)@users\.noreply\.github\.com>\|${GOOD_N} <(4496266\+trevornk|trevornk)@users\.noreply\.github\.com>$" || true)"
check "only bypassed commit is non-canonical" "$strays" 1

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
