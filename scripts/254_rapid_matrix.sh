#!/usr/bin/env bash
# #254 investigation, phase 2: bounded rapid/overlapping restore matrix.
#
# Extends scripts/254_lifecycle_harness.sh (which used ~3s-spaced cycles, not
# race-proof per the original report) with:
#   1. N fast back-to-back disable/enable cycles, NO settle delay between the
#      write and the readback -- as fast as the adb round-trip allows.
#   2. A structured OVERLAPPING schedule: a disable and a re-enable write
#      issued as two backgrounded shell jobs with a small negative/near-zero
#      offset, so the second write can land before the first's effects have
#      necessarily settled -- approximating what a poorly-serialized
#      automation macro (or two overlapping macros) could do.
#
# Every snapshot records THREE independent readouts, not just the
# ServiceRecord count (per investigation instruction: ServiceRecord count
# alone is not app-singleton/bound proof):
#   - enabled_accessibility_services (Settings.Secure state)
#   - `dumpsys activity services` ServiceRecord count for the package
#   - `dumpsys accessibility` Bound services count mentioning the package
#     (distinguishes "coalesced toggle, never unbound" from "genuine
#     unbind+rebind": if Enabled still lists the component throughout a
#     disable attempt, the write never took -- a distinct failure mode from
#     an observed unbind).
#   - app pid (process survival, not just service record)
#
# Restores the EXACT captured baseline on exit via trap, same as the phase-1
# harness. Safe to re-run. Read-only for everything except
# enabled_accessibility_services, which is fully restored.
#
# Usage: ./254_rapid_matrix.sh <serial> <package> <n_fast_cycles> <outfile>
set -euo pipefail
SERIAL="$1"
PKG="$2"
N="${3:-50}"
OUT="${4:-/tmp/254_rapid_matrix.jsonl}"
ADB="adb -s $SERIAL"

BASELINE=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "Captured baseline: $BASELINE"

restore() {
  $ADB shell settings put secure enabled_accessibility_services "$BASELINE" >/dev/null
}
trap restore EXIT

DISABLED=$(python3 - "$BASELINE" "$PKG" <<'PYEOF'
import sys
baseline, pkg = sys.argv[1], sys.argv[2]
parts = [p for p in baseline.split(':') if not p.startswith(pkg + "/")]
print(':'.join(parts))
PYEOF
)
echo "Disabled-state string: $DISABLED"

snapshot() {
  local label="$1"
  local eas svcs bound pid ts
  eas=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
  svcs=$($ADB shell dumpsys activity services "$PKG" 2>/dev/null | grep -c "ServiceRecord{" || true)
  # NOTE: `dumpsys accessibility`'s "Bound services" block identifies entries by
  # component LABEL (e.g. "Ramblr (Floating icon)" / "Ramblr (System controls)"),
  # not by package name -- grepping for $PKG here silently matches nothing. Match
  # on "Ramblr" (a substring of both component labels, and not shared by any
  # other installed accessibility service on this device) instead.
  bound=$($ADB shell dumpsys accessibility 2>/dev/null | awk '/Bound services/{f=1} /Enabled services/{f=0} f' | grep -c "label=Ramblr" || true)
  pid=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  ts=$(python3 -c 'import time; print(time.time())')
  python3 - "$label" "$eas" "$svcs" "$bound" "$pid" "$ts" <<'PYEOF' >> "$OUT"
import json, sys
label, eas, svcs, bound, pid, ts = sys.argv[1:7]
print(json.dumps({
    "ts": float(ts),
    "label": label,
    "enabled_accessibility_services_has_pkg": (":" + eas + ":").find(":com.trevornk.ramblr/") != -1 or eas.startswith("com.trevornk.ramblr/"),
    "service_record_count": int(svcs) if svcs.strip().isdigit() else svcs,
    "bound_count": int(bound) if bound.strip().isdigit() else bound,
    "pids": pid.split() if pid.strip() else [],
}))
PYEOF
}

echo "=== Phase A: $N fast back-to-back disable/enable cycles, no settle delay ==="
snapshot "fast_start"
for i in $(seq 1 "$N"); do
  $ADB shell settings put secure enabled_accessibility_services "$DISABLED"
  snapshot "fast${i}_after_disable"
  $ADB shell settings put secure enabled_accessibility_services "$BASELINE"
  snapshot "fast${i}_after_enable"
done
snapshot "fast_end"

echo "=== Phase B: overlapping schedule (5 rounds) ==="
# Fire disable, then re-enable almost immediately in the background while the
# first write's AMS-side processing may still be in flight, then snapshot.
for i in $(seq 1 5); do
  ( $ADB shell settings put secure enabled_accessibility_services "$DISABLED" ) &
  P1=$!
  ( $ADB shell settings put secure enabled_accessibility_services "$BASELINE" ) &
  P2=$!
  wait "$P1" "$P2" || true
  snapshot "overlap${i}_after_concurrent_writes"
  # Second round: enable-then-disable ordering (opposite), also concurrent.
  ( $ADB shell settings put secure enabled_accessibility_services "$BASELINE" ) &
  P3=$!
  ( $ADB shell settings put secure enabled_accessibility_services "$DISABLED" ) &
  P4=$!
  wait "$P3" "$P4" || true
  snapshot "overlap${i}_reverse_after_concurrent_writes"
  # Restore explicitly between rounds so each round starts from a known state.
  $ADB shell settings put secure enabled_accessibility_services "$BASELINE"
  snapshot "overlap${i}_restored"
done

restore
snapshot "final_restored"
trap - EXIT
echo "Rapid/overlapping matrix complete. Output: $OUT"
