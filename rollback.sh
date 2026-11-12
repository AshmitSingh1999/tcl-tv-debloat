#!/usr/bin/env bash
# Undo everything this project changed on the TV: re-enable every package the batches disabled,
# give Home back to the stock launcher, remove the custom launcher, and put Private DNS and the
# accessibility (remote-key helper) settings and the screensaver back to what they were.
#
# Usage:
#   TV_HOST=<tv ip> ./rollback.sh               full rollback, then reboot and verify
#   TV_HOST=<tv ip> ./rollback.sh --dry-run     print every command, send nothing to the TV
#   TV_HOST=<tv ip> ./rollback.sh --batch 02    re-enable only the packages of batches/02-*.txt
#   TV_HOST=<tv ip> ./rollback.sh --no-reboot   full rollback without the final reboot
#
# The TV must have USB/Network debugging on (Settings > Device Preferences > Developer options).
# Nothing here uninstalls a system package: the only uninstall is the launcher this project installed.
#
# Exit status: 0 everything verified, 1 a check failed (the FAIL lines say which), 2 bad usage.
set -euo pipefail
export LC_ALL=C
cd "$(dirname "$0")"

STOCK=com.google.android.tvlauncher            # what owned Home before (backup/home-resolved.txt)
STOCK_HOME=$STOCK/.MainActivity
OURS=com.gathin.tvhome                         # the launcher this project installs
ORIG=original-disabled.txt                     # packages that were already disabled before we started
# Original values of the settings this project touches ("null" = was not set).
ORIG_DNS_MODE=null
ORIG_DNS_HOST=null
ORIG_A11Y_SERVICES=null
ORIG_A11Y_ENABLED=0
ORIG_SCREENSAVER=com.google.android.backdrop/.Backdrop   # the screensaver before Aerial Views
AERIAL=com.neilturner.aerialviews                        # the screensaver app this project installs

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; }
DRY=""; ONLY=""; REBOOT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --no-reboot) REBOOT="" ;;
    --batch) ONLY="${2:-}"; [ -n "$ONLY" ] || { usage >&2; exit 2; }; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
[ -n "${TV_HOST:-}" ] || { echo "set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 ./rollback.sh" >&2; exit 2; }
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 2; }
[ -r "$ORIG" ] || { echo "cannot read $ORIG" >&2; exit 2; }

# One shell command on the TV. In a dry run it is printed instead.
tv() {
  if [ -n "$DRY" ]; then echo "  [dry-run] $*"; else "$PY" -I tools/tv.py "$@"; fi
}
# Read-only questions are asked even in a dry run when the TV answers; otherwise they return nothing.
ask() { "$PY" -I tools/tv.py "$@" 2>/dev/null | tr -d '\r' || true; }
# Package names of a batch file: one per line, '#' comments allowed.
pkgs_of() { sed -E 's/#.*$//' "$1" | awk 'NF {print $1}' | grep -E '^[A-Za-z0-9_.]+$' || true; }
put_or_delete() {  # namespace key value
  if [ "$3" = null ]; then tv "settings delete $1 $2"; else tv "settings put $1 $2 $3"; fi
}

if [ -n "$ONLY" ]; then
  FILES=$(ls batches/"$ONLY"*.txt 2>/dev/null || true)
  [ -n "$FILES" ] || { echo "no batch file matches batches/$ONLY*.txt" >&2; exit 2; }
else
  FILES=$(ls batches/*.txt | sort -r)        # newest batch first: the stock launcher comes back first
fi

FAIL=""
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAIL=1; }

echo "== 1. Re-enable packages"
WANT_ON=""
for f in $FILES; do
  echo "-- $f"
  for p in $(pkgs_of "$f"); do
    if grep -qxF "$p" "$ORIG"; then
      echo "  skip $p (was already disabled before this project)"
    else
      tv "pm enable --user 0 $p"
      WANT_ON="$WANT_ON $p"
    fi
  done
done

if [ -z "$ONLY" ]; then
  echo "== 2. Give Home back to the stock launcher"
  tv "pm enable --user 0 $STOCK"
  tv "cmd package set-home-activity --user 0 $STOCK_HOME"
  if [ -z "$DRY" ]; then
    NOW=$(ask 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1)
    case "$NOW" in
      "$STOCK"/*) pass "Home resolves to $NOW" ;;
      *) fail "Home resolves to '${NOW:-nothing}', not $STOCK. The custom launcher is left installed so the TV keeps a Home."
         echo "Fix by hand: tools/tv.py 'cmd package set-home-activity --user 0 $STOCK_HOME'"; exit 1 ;;
    esac
  fi

  echo "== 3. Remove the custom launcher (only after Home went back)"
  put_or_delete secure enabled_accessibility_services "$ORIG_A11Y_SERVICES"
  tv "settings put secure accessibility_enabled $ORIG_A11Y_ENABLED"
  if [ -n "$DRY" ] || ask 'pm list packages' | grep -qx "package:$OURS"; then
    tv "pm uninstall $OURS"
  else
    echo "  $OURS is not installed, nothing to remove"
  fi
  tv "input keyevent KEYCODE_HOME"

  echo "== 4. Private DNS back to its original value"
  put_or_delete global private_dns_mode "$ORIG_DNS_MODE"
  put_or_delete global private_dns_specifier "$ORIG_DNS_HOST"

  echo "== 5. Screensaver back to the original, Aerial Views removed"
  tv "settings put secure screensaver_components $ORIG_SCREENSAVER"
  if [ -n "$DRY" ] || ask 'pm list packages' | grep -qx "package:$AERIAL"; then
    tv "pm uninstall $AERIAL"
  else
    echo "  $AERIAL is not installed, nothing to remove"
  fi
fi

if [ -n "$DRY" ]; then
  echo "== dry run: nothing was sent to the TV"
  exit 0
fi

verify() {
  DISABLED=$(ask 'pm list packages -d --user 0' | sed -n 's/^package://p' | sort)
  [ -n "$DISABLED" ] || { fail "could not read the disabled-package list from the TV"; return; }
  for p in $WANT_ON; do
    if printf '%s\n' "$DISABLED" | grep -qxF "$p"; then fail "$p is still disabled"; else pass "$p enabled"; fi
  done
  if [ -z "$ONLY" ]; then
    if [ "$DISABLED" = "$(sort "$ORIG")" ]; then pass "disabled-package list is identical to the original ($ORIG)"
    else fail "disabled-package list differs from the original:"; diff <(sort "$ORIG") <(printf '%s\n' "$DISABLED") || true; fi
    NOW=$(ask 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -n 1)
    case "$NOW" in "$STOCK"/*) pass "Home is $NOW" ;; *) fail "Home is '${NOW:-unknown}'" ;; esac
    if ask 'pm list packages' | grep -qx "package:$OURS"; then fail "$OURS is still installed"; else pass "$OURS removed"; fi
    SAVER=$(ask 'settings get secure screensaver_components' | tail -n 1)
    [ "$SAVER" = "$ORIG_SCREENSAVER" ] && pass "screensaver = $SAVER" || fail "screensaver = $SAVER (wanted $ORIG_SCREENSAVER)"
    if ask 'pm list packages' | grep -qx "package:$AERIAL"; then fail "$AERIAL is still installed"; else pass "$AERIAL removed"; fi
    MODE=$(ask 'settings get global private_dns_mode' | tail -n 1)
    [ "$MODE" = "$ORIG_DNS_MODE" ] && pass "private_dns_mode = $MODE" || fail "private_dns_mode = $MODE (wanted $ORIG_DNS_MODE)"
  fi
}

if [ -z "$ONLY" ] && [ -n "$REBOOT" ]; then
  echo "== 6. Reboot (Android writes package state ~10 s after a change, so wait first)"
  tv "sync"
  sleep 15
  "$PY" -I tools/tv.py --reboot || true
  echo "waiting for the TV to come back..."
  UP=""
  for _ in $(seq 1 40); do
    sleep 6
    if [ "$(ask 'getprop sys.boot_completed' | tail -n 1)" = 1 ]; then UP=1; break; fi
  done
  [ -n "$UP" ] || { fail "the TV did not answer within 4 minutes after the reboot; run ./rollback.sh --no-reboot again to verify"; exit 1; }
  sleep 20
fi

echo "== Verify"
verify
if [ -n "$FAIL" ]; then echo "ROLLBACK INCOMPLETE: see the FAIL lines above."; exit 1; fi
echo "ROLLBACK COMPLETE."
