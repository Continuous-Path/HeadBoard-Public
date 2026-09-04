#!/usr/bin/env bash
# hb — entry point for gradle/adb work on HeadBoard (lean sibling of JustType's ./jt).
#
# Common flows:
#   ./hb install -fp    fresh install (wipe app + data) and grant all perms via adb
#   ./hb install        plain install over the existing build
#   ./hb perms          grant perms only (camera, notifications, overlay, accessibility)
#   ./hb test           unit tests
#
# Run `./hb help` for the full reference.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_PROJECT="${PROJECT_ROOT}/HeadBoard"
GRADLEW="${GRADLE_PROJECT}/gradlew"

APP_PKG="org.continuouspath.headboard"
# Accessibility services store the fully-qualified flattenToString form.
ACCESSIBILITY_SERVICE_ID="${APP_PKG}/${APP_PKG}.CursorAccessibilityService"

# ── Pretty output ────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
	BOLD=$'\033[1m'
	DIM=$'\033[2m'
	GREEN=$'\033[32m'
	RED=$'\033[31m'
	RESET=$'\033[0m'
else
	BOLD="" DIM="" GREEN="" RED="" RESET=""
fi

# log/err on stderr so command substitutions capturing adb output stay clean.
log() { echo "${DIM}hb:${RESET} $*" >&2; }
ok()  { echo "${GREEN}hb:${RESET} $*"; }
err() { echo "${RED}hb: $*${RESET}" >&2; }

# ── Gradle ───────────────────────────────────────────────────────────────

run_gradle() {
	log "$ gradlew $*"
	(cd "$GRADLE_PROJECT" && "$GRADLEW" "$@")
}

# ── adb helpers ──────────────────────────────────────────────────────────

ADB_BIN=""
resolve_adb() {
	if [[ -n "$ADB_BIN" ]]; then return 0; fi
	if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
		ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
	elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]]; then
		ADB_BIN="${ANDROID_SDK_ROOT}/platform-tools/adb"
	elif command -v adb >/dev/null 2>&1; then
		ADB_BIN="$(command -v adb)"
	else
		err "adb not found. Set ANDROID_HOME (or ANDROID_SDK_ROOT), or put adb on PATH."
		return 1
	fi
	return 0
}

# Run adb, echoing the command. Honors ANDROID_SERIAL natively — export it to
# target a specific device when several are attached.
adb_sh() {
	log "$ adb $*"
	"$ADB_BIN" "$@"
}

# Runtime permissions (pm grant). Best-effort: POST_NOTIFICATIONS doesn't
# exist below API 33 and BLUETOOTH_CONNECT below 31; a failed grant of an
# inapplicable permission is fine.
perms_runtime_grant() {
	adb_sh shell pm grant "$APP_PKG" android.permission.CAMERA
	adb_sh shell pm grant "$APP_PKG" android.permission.POST_NOTIFICATIONS || true
	adb_sh shell pm grant "$APP_PKG" android.permission.BLUETOOTH_CONNECT || true
}

perms_runtime_revoke() {
	adb_sh shell pm revoke "$APP_PKG" android.permission.CAMERA || true
	adb_sh shell pm revoke "$APP_PKG" android.permission.POST_NOTIFICATIONS || true
	adb_sh shell pm revoke "$APP_PKG" android.permission.BLUETOOTH_CONNECT || true
}

# Overlay (SYSTEM_ALERT_WINDOW) — floating camera window + cursor. $1 = allow|deny.
perms_overlay() { adb_sh shell appops set "$APP_PKG" SYSTEM_ALERT_WINDOW "$1"; }

# Accessibility service. enabled_accessibility_services is a SHARED,
# colon-separated list — merge in/out so other services (JustType Nav,
# TalkBack, …) survive. Overwriting it would silently disable them.
perms_accessibility_enable() {
	local current new
	# Quiet read (no adb_sh): this runs inside a command substitution.
	current="$("$ADB_BIN" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
	if [[ "$current" == "null" || -z "$current" ]]; then
		new="$ACCESSIBILITY_SERVICE_ID"
	elif [[ ":$current:" == *":$ACCESSIBILITY_SERVICE_ID:"* ]]; then
		new="$current"  # already enabled
	else
		new="${current}:${ACCESSIBILITY_SERVICE_ID}"
	fi
	adb_sh shell settings put secure enabled_accessibility_services "$new"
	adb_sh shell settings put secure accessibility_enabled 1
}

perms_accessibility_disable() {
	local current new
	current="$("$ADB_BIN" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
	if [[ "$current" == "null" || -z "$current" ]]; then return 0; fi
	# paste is the pipe terminus and always exits 0, so an empty result (we
	# were the only entry) doesn't trip set -e.
	new="$(printf '%s' "$current" | tr ':' '\n' | grep -vxF "$ACCESSIBILITY_SERVICE_ID" | paste -sd ':' -)"
	adb_sh shell settings put secure enabled_accessibility_services "$new"
	[[ -z "$new" ]] && adb_sh shell settings put secure accessibility_enabled 0
	return 0
}

cmd_perms() {
	local mode="enable"
	while [[ $# -gt 0 ]]; do
		case "$1" in
			-y|--yes|--enable)  mode="enable";  shift ;;
			-n|--no|--disable)  mode="disable"; shift ;;
			-h|--help)
				echo "usage: ./hb perms [-y|--enable | -n|--disable]   (default: -y)"
				return 0 ;;
			*) err "perms: unknown flag: $1 (use -y to enable, -n to disable)"; return 2 ;;
		esac
	done
	resolve_adb || return 1
	# Best-effort: attempt every grant even if one fails (e.g. a grant
	# already in the target state).
	set +e
	if [[ "$mode" == "enable" ]]; then
		log "granting HeadBoard perms: camera + notifications + overlay + accessibility"
		perms_runtime_grant
		perms_overlay allow
		perms_accessibility_enable
		ok "done. Toggle HeadBoard on from the app (or it may already be running)."
	else
		log "revoking HeadBoard perms"
		perms_runtime_revoke
		perms_overlay deny
		perms_accessibility_disable
		ok "done."
	fi
	set -e
	return 0
}

# ── install ──────────────────────────────────────────────────────────────

INSTALL_FRESH=false
INSTALL_PERMS=false
parse_install_flags() {
	INSTALL_FRESH=false
	INSTALL_PERMS=false
	while [[ $# -gt 0 ]]; do
		case "$1" in
			-f|--fresh)            INSTALL_FRESH=true;  shift ;;
			-p|--perms)            INSTALL_PERMS=true;  shift ;;
			-fp|-pf|--fresh-perms) INSTALL_FRESH=true; INSTALL_PERMS=true; shift ;;
			*) err "install: unknown flag: $1 (supported: -f/--fresh, -p/--perms, -fp)"; return 2 ;;
		esac
	done
	return 0
}

# Fresh = full removal (app + all data) before reinstall. `adb uninstall`
# drops the package and its data; a missing install is a no-op, not an error.
install_fresh_uninstall() {
	resolve_adb || return 1
	log "fresh: removing $APP_PKG (app + all data) before reinstall"
	if "$ADB_BIN" uninstall "$APP_PKG" >/dev/null 2>&1; then
		log "fresh: previous install removed"
	else
		log "fresh: nothing to remove (not currently installed)"
	fi
}

run_install() {
	local gradle_task="$1"; shift
	parse_install_flags "$@" || return $?
	if $INSTALL_FRESH; then install_fresh_uninstall || return 1; fi
	run_gradle "$gradle_task" || return 1
	if $INSTALL_PERMS; then cmd_perms; fi
}

# ── Subcommands ──────────────────────────────────────────────────────────

cmd_build()           { run_gradle :app:assembleDebug; }
cmd_build_release()   { run_gradle :app:assembleRelease; }
cmd_install()         { run_install :app:installDebug "$@"; }
cmd_install_release() { run_install :app:installRelease "$@"; }
cmd_test()            { run_gradle :app:testDebugUnitTest; }
cmd_clean()           { run_gradle clean; }

cmd_uninstall() {
	local revoke=false
	while [[ $# -gt 0 ]]; do
		case "$1" in
			-p|--perms) revoke=true; shift ;;
			*) err "uninstall: unknown flag: $1 (supported: -p/--perms)"; return 2 ;;
		esac
	done
	resolve_adb || return 1
	# Revoke while the app is still installed (appops needs the package present).
	if $revoke; then set +e; perms_runtime_revoke; perms_overlay deny; perms_accessibility_disable; set -e; fi
	adb_sh uninstall "$APP_PKG" || true
}

cmd_logcat() { resolve_adb || return 1; adb_sh logcat --pid="$("$ADB_BIN" shell pidof -s "$APP_PKG" | tr -d '\r')" "$@"; }

cmd_help() {
	cat <<EOF
${BOLD}hb — HeadBoard project runner${RESET}

usage: ./hb <command> [flags]

${BOLD}build${RESET}
  build               assembleDebug
  build-release       assembleRelease (team key if available)

${BOLD}install${RESET} (flags combine; also on install-release)
  install             installDebug
  install -f          uninstall first (wipes app + data)
  install -p          grant perms after install
  install -fp         both: fresh install + grant all perms
  uninstall [-p]      remove the app (-p also revokes perms first)

${BOLD}device perms${RESET} (adb; export ANDROID_SERIAL to pick a device)
  perms [-y]          grant: CAMERA, POST_NOTIFICATIONS, BLUETOOTH_CONNECT,
                      overlay (SYSTEM_ALERT_WINDOW), accessibility service
                      (merged into the shared secure list — JustType/TalkBack survive)
  perms -n            revoke all of the above

${BOLD}misc${RESET}
  test                unit tests (testDebugUnitTest)
  clean               gradle clean
  logcat [args]       logcat filtered to the HeadBoard process
  help                this text
EOF
}

main() {
	local cmd="${1:-help}"
	[[ $# -gt 0 ]] && shift
	case "$cmd" in
		build)            cmd_build "$@" ;;
		build-release)    cmd_build_release "$@" ;;
		install)          cmd_install "$@" ;;
		install-release)  cmd_install_release "$@" ;;
		uninstall)        cmd_uninstall "$@" ;;
		perms)            cmd_perms "$@" ;;
		test)             cmd_test "$@" ;;
		clean)            cmd_clean "$@" ;;
		logcat)           cmd_logcat "$@" ;;
		help|-h|--help)   cmd_help ;;
		*) err "unknown command: $cmd (see ./hb help)"; return 2 ;;
	esac
}

main "$@"
