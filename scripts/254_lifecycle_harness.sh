#!/usr/bin/env bash
# #254 investigation: bounded, repeatable observation harness for the
# WhisperAccessibilityService disable/enable lifecycle.
#
# Records, for each attempt: the enabled_accessibility_services string, the
# `dumpsys activity services` ServiceRecord count/identity for the target
# package, and app process state -- to a JSON-lines file for offline analysis.
# Restores the EXACT captured baseline after every attempt (not an assumed
# list) and again at the very end, so it is safe to re-run.
#
# Usage: ./254_lifecycle_harness.sh <serial> <package> <n_attempts> <outfile>
set -euo pipefail
SERIAL="$1"
PKG="$2"
N="${3:-5}"
OUT="${4:-/tmp/254_harness.jsonl}"
ADB="adb -s $SERIAL"

BASELINE=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
echo "Captured baseline: $BASELINE"

restore() {
  $ADB shell settings put secure enabled_accessibility_services "$BASELINE" >/dev/null
}
trap restore EXIT

snapshot() {
  local label="$1"
  local eas svcs pid instance_line
  eas=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
  svcs=$($ADB shell dumpsys activity services "$PKG" 2>/dev/null | grep -c "ServiceRecord{" || true)
  pid=$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  python3 - "$label" "$eas" "$svcs" "$pid" "$ts" <<'PYEOF' >> "$OUT"
import json, sys
label, eas, svcs, pid, ts = sys.argv[1:6]
print(json.dumps({
    "ts": ts,
    "label": label,
    "enabled_accessibility_services": eas,
    "service_record_count": int(svcs) if svcs.strip().isdigit() else svcs,
    "pids": pid.split() if pid.strip() else [],
}))
PYEOF
}

# Disabled variant of enabled_accessibility_services: strip our component,
# leaving any other enabled services (e.g. Lawnchair) untouched.
DISABLED=$(python3 - "$BASELINE" "$PKG" <<'PYEOF'
import sys
baseline, pkg = sys.argv[1], sys.argv[2]
parts = [p for p in baseline.split(':') if not p.startswith(pkg + "/")]
print(':'.join(parts))
PYEOF
)

echo "Disabled-state string: $DISABLED"
snapshot "start"

for i in $(seq 1 "$N"); do
  echo "=== attempt $i/$N: disable ==="
  $ADB shell settings put secure enabled_accessibility_services "$DISABLED"
  snapshot "attempt${i}_after_disable"
  sleep 0.3
  echo "=== attempt $i/$N: re-enable ==="
  $ADB shell settings put secure enabled_accessibility_services "$BASELINE"
  snapshot "attempt${i}_after_enable"
  sleep 0.3
done

restore
snapshot "final_restored"
trap - EXIT
echo "Harness complete. Output: $OUT"
