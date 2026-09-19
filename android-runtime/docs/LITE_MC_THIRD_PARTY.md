# Runtime and dependency provenance

Lite-MC's Android product UI/services were added in `com/litemc/launcher` and
`assets/litemc`. The surrounding Android/JVM/native runtime is derived from
AngelAuraMC/Amethyst-Android (baseline commit
`3ad1100904fef8e3aaa7f50f1b6a05cef918b29c`) and PojavLauncher.
The repository's LGPL license and upstream copyright notices are retained.

Included dependencies also retain their own terms; the root license is not a
claim that every embedded library has the same license. Sources include LWJGL,
OpenAL, Mesa/rendering bridges, AndroidX, Gson, Apache Commons, MioLibPatcher and
androidnsbypass. Gradle build scripts, `.gitmodules`, native makefiles and the
existing license files are the authoritative inventory for this source tree.

For reproducible Windows builds, the following JitPack artifacts were resolved
to local AAR files in `app_pojavlauncher/libs` during the previous prototype work:

| Component | Requested version |
| --- | --- |
| checkerboarddrawable | 1.0.2 |
| portrait-sdp | ed33e89cbc |
| portrait-ssp | 6c02fd739b |
| ExtendedView | 1.0.0 |
| android_gamepad_remapper | 2.0.3 |
| virtual-joystick-android | 1.14 |

Their original coordinates remain adjacent to the commented Gradle declarations.
Gradle currently includes a build-time Maven mirror for connectivity on this
workstation; that does not change the launcher's official Minecraft/Fabric sources.

Android-compatible OpenJDK runtimes are obtained by the runtime from
AngelAuraMC/angelauramc-openjdk-build. Minecraft itself is downloaded from its
official distribution endpoints at the user's request, not included in the APK.

Before any public release: complete a dependency-by-dependency license/source
inventory, supply corresponding modified source and build instructions, verify
the clean build on another machine, replace development signing with the owner's
production key, and complete the device verification matrix. This development
handoff is not a completed distribution-license audit.
