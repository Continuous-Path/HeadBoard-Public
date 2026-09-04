# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo contains

This repo contains **HeadBoard** (`HeadBoard/`) — an accessibility app that tracks the user's head via MediaPipe Face Landmarker, moves a system cursor, and injects gestures. App id `org.continuouspath.headboard`. Mostly Java, some Kotlin.

HeadBoard cooperates at runtime with two companion apps that live in **separate repos** and are installed separately on the device:

- **OpenBoard** — our fork of the open-source OpenBoard IME, modified for "swype"/gesture typing driven by HeadBoard's head-tracking cursor. Optional companion keyboard. App id `org.dslul.openboard.inputmethod.latin`. Repo: `Continuous-Path/OpenBoard-HB` (split out of this repo in Aug 2026 with its history; formerly the `openboard/` dir here).
- **JustType** — a separate accessibility IME (unique 8-key layout). HeadBoard sends it broadcasts (package `org.continuouspath.justtype`).

> Stale paths: `.cursorrules`, `NOTES.md`, and `AIDL_SERVICE_README.md` refer to HeadBoard as `Android/` (a dir that no longer exists) and may mention an in-repo `openboard/` dir. The code lives in `HeadBoard/`; OpenBoard is no longer in this repo.

## Build & install

- Build: `cd HeadBoard && ./gradlew :app:assembleDebug` → `HeadBoard/app/build/outputs/apk/debug/app-debug.apk` (any `:Android:` prefix in the docs is stale)
- Install: `adb install -r <apk>`. Then enable HeadBoard's accessibility service; to use the companion keyboard, build/install OpenBoard from its own repo and select it as the IME in device settings.

Requires a physical device with a camera — head tracking can't be exercised in an emulator. MediaPipe's `face_landmarker.task` model downloads on first build.

## Inter-app communication

**Broadcast Intents are authoritative.** AIDL interfaces and a bound service exist in the tree but are **unused / not wired in production** — do not migrate to AIDL or remove broadcast paths. `AIDL_SERVICE_README.md` describes the aspirational AIDL design only.

- HeadBoard → OpenBoard (head cursor injected as touch/key events, trail color, key popups): actions namespaced `org.dslul.openboard.inputmethod.latin.ACTION_RECEIVE_MOTION_EVENT` / `ACTION_RECEIVE_KEY_EVENT` / `ACTION_CHANGE_TRAIL_COLOR` / etc. — sent from `KeyboardManager.java`, received by OpenBoard's `IMEEventReceiver.java` (in the OpenBoard-HB repo).
- OpenBoard → HeadBoard: `org.continuouspath.headboard.ACTION_IME_SWIPE_START` / `ACTION_IME_LONGPRESS_ANIMATION` / `ACTION_IME_STATE_CHANGED` — received by `KeyboardEventReceiver.java`.
- HeadBoard ↔ JustType: `org.continuouspath.justtype.*` (HeadBoard sends `EXTERNAL_JOYSTICK_INPUT`, `ACTION_EXTERNAL_SWITCH` (a HeadBoard trigger bound to "JustType Switch 1/2", press/release edges), `ACTION_HEAD_TRACKING_RESUME`, `CLEAR_HIGHLIGHTS`; JustType replies `ACTION_HEAD_TRACKING_ENABLED/DISABLED/POP_OUT`).
- Custom permissions: `org.dslul.openboard.inputmethod.latin.permission.RECEIVE_HEADBOARD_EVENT`, `org.continuouspath.headboard.permission.RECEIVE_IME_EVENT`, `org.continuouspath.headboard.permission.RECEIVE_JUSTTYPE_EVENT`.
- The authoritative action lists live in the source files, not in the markdown docs. Changes to these broadcasts must stay in sync with the OpenBoard-HB repo (and the JustType app).

## Editing rules

- Changing `android:exported`, custom permissions, broadcast actions, or the accessibility service config is load-bearing for cross-app comms — a wrong change silently breaks integration on-device. Flag such changes before making them.
- Integration with OpenBoard/JustType is runtime-only (broadcasts); there is no build-time dependency.
- Don't migrate broadcasts to AIDL by default.

## Signing & releases

- **Debug builds** sign with AGP's default debug keystore (`~/.android/debug.keystore`). The AOSP platform test key that used to be committed here has been removed — its private half ships in every AOSP checkout.
- **Release builds** sign with the shared Continuous Path team key (same key as JustType and OpenBoard; keystore `~/.justtype/justtype-release.jks`, password in the macOS Keychain, both installed by JustType's `./jt signing-setup`). A machine without it produces an **unsigned** release rather than falling back to another key. CI supplies `JUSTTYPE_*` gradle properties from repo secrets, fails if the secret is missing, and re-checks the output certificate.
- **Signature caveat:** OpenBoard's `RECEIVE_HEADBOARD_EVENT` permission is signature-protected. HeadBoard and OpenBoard must be signed with the same key to talk — install matching build types on a device (release↔release or debug↔debug), never mixed.
- **Release pipeline** (`.github/workflows/release.yml`): pushing a `v*` tag builds a signed release APK and publishes it to `Continuous-Path/HeadBoard-Releases` (stable link: `releases/latest/download/headboard.apk`). Mirrors JustType's pipeline (docs in JustType1's `docs/release.md`). Release builds keep R8/minify OFF — it crashed HeadBoard at runtime.

## Repo

Branches are merged locally (no PR review gate). `origin` = `Continuous-Path/HeadBoard`; `upstream` = `google/project-headboard`, the project this was forked from. OpenBoard history was split into `Continuous-Path/OpenBoard-HB` via `git filter-repo`; the old openboard commits remain in this repo's history.
