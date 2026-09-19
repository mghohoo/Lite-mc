# Lite-MC Android final check

Date: 2026-09-17

## Static and regression checks: passed

- `node tests/mobile-ui-regression.mjs`: **16/16 passed**. This covers the
  Lite-MC local UI contract, offline-name request, account-model ordering,
  touch-control payload, malicious remote-UI fallback, and native action
  routing.
- `node tests/downloader-regression.mjs`: **14/14 passed**. This covers cached
  and offline preparation, missing/corrupt files, required library failures,
  inherited versions, and interrupted preparation.
- `powershell scripts/test-lite-mobile.ps1`: **22 passed, 0 failed, 3 skipped**.
  The skipped checks are intentionally build/APK/device checks because this
  final check did not run Gradle in parallel with the active `assembleDebug`.

## Source-level acceptance checks: passed

- The launcher entry is `com.litemc.launcher.LiteActivity`; it alone declares
  the `MAIN`/`LAUNCHER` intent filter. The old `.LauncherActivity` is declared
  with `android:enabled="false"`.
- `PojavProfile.getCurrentProfileContent()` explicitly recognizes
  `LiteRuntimeAccount.PROFILE` (`lite-session`) and reads the private,
  Keystore-encrypted runtime account handoff. The Lite activity sets that
  profile before install and launch. This closes the previously alleged
  missing-account bridge; it is not a current P0 finding.
- Debug identity is `com.litemc.launcher.android.dev` (base application ID
  plus `.dev`) and uses the dedicated `lite-debug.keystore` / `lite-mc-development`
  signing configuration, allowing coexistence with an earlier differently
  named debug installation.
- `LiteActivity` dispatches a selected installed instance to `MainActivity`;
  downloader regressions also confirm it does not report successful setup
  after an interrupted or failed required download.

## Actual findings / release blockers

1. **No real-device or emulator smoke test has passed.** The available
   `Pixel_Fold_API_34` emulator aborts before exposing ADB with the emulator
   error `It seems too many emulator instances are running on this machine`.
   No device serial is available. The APK must not be described as
   gameplay-validated or released for end users until it has passed install,
   UI, download, and one real Java-edition launch on a clean emulator or,
   preferably, an ARM64 Android device.
2. Microsoft device login, skin upload, CurseForge (requires a permitted key),
   Fabric installation/mod loading, touch input in the game process, renderer
   compatibility, process-return/exit handling, and actual Minecraft launch
   remain unverified. Compilation and contract tests do not prove any of them.
3. This check did not start Gradle because root already owns the full
   `assembleDebug` run. Record that build result separately once it finishes;
   a successful build does not remove blocker 1.

## Required live acceptance sequence

1. Verify the fresh APK signature/package identity, install it, and launch
   `LiteActivity` using `adb` on a clean device.
2. Select a supported release, download an instance, then launch once with a
   valid offline name; verify return to the launcher after game exit.
3. On ARM64 hardware, repeat with the supported renderer and touch controls.
4. With an own Microsoft public client ID and a licensed Java account, complete
   device-code login and launch; do not record tokens in logs/screenshots.
5. Install a Fabric instance and one compatible Modrinth mod; separately test
   CurseForge only with an authorized API key.
# Root final artifact verification (2026-09-17)

After the agent review, the root agent rebuilt the latest source successfully,
including ProgressKeeper exactly-once/reentrancy fixes and Activity-destruction
guards. Regression counts: UI 16/16, downloader 14/14, ProgressKeeper 9/9 with
1,000 race rounds; static smoke 22 PASS / 0 FAIL / 3 SKIP.

Artifact: `D:\mc-lite\release\Lite-MC-Android-1.1.0-dev.apk`, 144,632,367 bytes.
SHA-256: `14F236C71DEC1F58EAA9E651409C9A34D477937861587199D8D111AA69BC3111`.
`apksigner verify`: v1/v2 valid, RSA 3072, CN=Lite-MC Development. `aapt` confirms
package `com.litemc.launcher.android.dev`, version `1.1.0-dev`, min SDK 23 and
launch entry `com.litemc.launcher.LiteActivity`. Bundled `assets/litemc` files verified.

Browser-only layout check: 390×844 mobile home, settings, in-place English switch,
and surviving version selector; no browser console errors observed in that check.
No native bridge was mocked to claim Android success. `adb devices -l` still empty.
The earlier device-verification blocker remains; this is a development handoff,
not a production acceptance sign-off.
