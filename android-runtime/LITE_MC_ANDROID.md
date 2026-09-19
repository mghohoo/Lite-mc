# Lite-MC Mobile — development build

This is the active Android project. `../android/` is an earlier, non-runnable
architecture sketch. The former `Lite-MC-Android-1.0-debug.apk` is a rebranding
prototype and is **not** the independent Lite-MC product described here.

## Product and runtime boundary

- Product code: `app_pojavlauncher/src/main/java/com/litemc/launcher/`.
- Original mobile UI, skin preview and touch layout: `app_pojavlauncher/src/main/assets/litemc/`.
- Product entry: `com.litemc.launcher.LiteActivity`; the old launcher Activity is disabled.
- Runtime: Amethyst/Pojav's Android Java, graphics, input, library preparation and
  game Activity. This part is reused, modified and attributed, not claimed as our own engine.

Implemented: separate install/select/launch flow; official Vanilla metadata and
Fabric stable loader; per-instance directories; offline profiles; own-client
Microsoft device authorization and encrypted persistent sessions; official skin
upload; Steve/Alex local previews with restrained idle movement; Modrinth and
CurseForge search plus required dependency downloads; in-app Mod file listing;
Chinese/English preferences; memory and touch-button scaling.

## Important test-build limitations

- **No successful Android device/gameplay test yet.** Compilation and desktop
  browser rendering do not prove that a phone can enter a Minecraft world.
- The local emulator exits with an instance-count error before ADB connects.
  Connect a device for the remaining tests in `docs/LITE_MC_TEST_PLAN.md`.
- Microsoft login requires a Lite-MC-owned registered public client ID, configured
  in Settings. It does not borrow an upstream launcher's application identity.
  Account approval/entitlement and interactive login have not been verified here.
- CurseForge requires a permitted API key entered in Settings. It is stored in
  Android Keystore-backed encrypted private storage, not a shared release key.
- Offline skin selection is a **local preview**, not a universal in-game skin mod.
- Initial loader support is Vanilla/Fabric, not Forge/NeoForge/Quilt.
- Initial language support is Chinese/English. Full desktop feature parity is not
  yet asserted. No arbitrary JAR installer or custom JVM/environment editor is exposed.
- Initial installation needs network access for game files and the Android-compatible
  JRE. Game assets/metadata use official Minecraft and Fabric sources; Android JRE
  builds are runtime dependencies supplied by the upstream runtime project.
- Downloads should remain in the foreground. Partial downloads are not treated as
  installed versions. Reinstalling reuses valid cached files.

## Build on this workstation

PowerShell, from this directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\OpenJDK\jdk-22.0.2'
$env:HOST_OS = 'windows'
.\gradlew.bat :app_pojavlauncher:assembleDebug --console=plain --no-daemon
```

Android SDK location is in ignored `local.properties`. Development builds use a
local `app_pojavlauncher/lite-debug.keystore`, alias `lite-mc-development` and the
conventional **non-secret development password** `android`. Do not use this
development certificate/password for a public production release. A missing key
can be recreated with JDK keytool (RSA 3072); preserve the key if future dev APKs
must upgrade an existing installation.

Package: `com.litemc.launcher.android.dev`; Android 6+.
It can coexist with the earlier `.debug` prototype and does not require deleting
that prototype's data. Uninstalling the new development package deletes its own
private sessions and application-scoped game data.

## Repeatable checks

```powershell
node tests/mobile-ui-regression.mjs
node tests/downloader-regression.mjs
node tests/progresskeeper-regression.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-lite-mobile.ps1
```

The Java regression harnesses compile the real target classes with narrowly scoped
stubs. The UI harness executes the real JS with a minimal DOM/Canvas fixture.
Neither is an Android device test. `scripts/preview-lite-mobile.mjs` serves the
actual UI on loopback port 4178 with no native bridge or fake installed games.

## Source and attribution

Runtime baseline: [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)
commit `3ad1100904fef8e3aaa7f50f1b6a05cef918b29c`, built on
[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher).
The original `LICENSE`, copyright headers, native components and submodule source
remain in this checkout. See `docs/LITE_MC_THIRD_PARTY.md`. Do not distribute a
release as wholly original runtime code or omit corresponding source/notices.
