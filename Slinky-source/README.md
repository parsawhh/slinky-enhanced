# Slinky source reconstruction

## Linux

The same Java payloads and shared JNI/JVMTI implementation now build on Linux. The ready-made Linux x86-64 bundle contains `slinky`, a Java console launcher, `libSlinkyNative.so`, and the three payload variants. Extract it and run `./slinky` with Lunar/Minecraft 1.8.9 already open. Use `./slinky --list` and `./slinky --pid PID` for explicit selection.

The launcher needs a full JDK 11+ with `jdk.attach`; the game may retain its own Java 8+ runtime. The supplied native binary needs glibc 2.34+ and GLIBCXX_3.4.29+ (Ubuntu 22.04+/Mint 21+/Debian 12+ class systems). Run as the same user as the game. Linux uses standard [JVM agent loading](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html).

Build from this source with `./build-linux.sh`. It verifies pinned dependencies, recompiles the existing Java source, produces all naming variants and the Linux native bridge. Missing dependencies can be restored explicitly with `--restore-dependencies`. The Windows build remains `build.ps1`.

Linux attachment, class rewriting and unload/reload passed eight real-JVM fixture tests under Ubuntu 24.04/WSL, including Unicode paths. All Linux Java class bodies/assets match their Windows counterparts. Actual Linux Lunar rendering/menu behavior remains untested; see `evidence/linux-integration-results.json` and `dist/linux-x86_64/README-linux.txt`.

## Current console and reload build

`dist/Slinky.exe` now opens a console, shows startup progress and errors, and keeps the window open until Enter. Use the menu's **Unload**, then launch it again to restart modules in the same Lunar session. Compatible updated class bodies are replaced live; the installed game hooks are reused rather than duplicated. An active instance is reported as skipped.

The live migration succeeded in the user's existing Lunar process (PID 27960), reinitialized 66 modules, and the user confirmed the menu works. This release also passed the active-instance skip check. See `evidence/reload-runtime.json` and `evidence/reload-runtime.log`.

This release intentionally improves startup/unload behavior beyond the recovered original. It retains all 509 original classes and adds one `ReloadState` helper. The original reconstruction comparison below describes the earlier verified source revision, preserved in `Slinky-source-verified.zip`; the current lifecycle changes and tests are documented in `evidence/reload-changes.txt`. Archived earlier evidence is under `evidence/final-java/` with `before-reload-` prefixes.

For command-line diagnostics: `Slinky.exe --self-test`, `Slinky.exe --test-log "path"`, or `Slinky.exe --no-pause` (launch without waiting for Enter). Full native details remain in the startup log; routine class bookkeeping is hidden from the console.

This project reconstructs the inspected `Slinky.exe` with SHA-256 `501d7c83e2010937350fee800e37631e1499ec9b2693a32493db71e5f40b0272`. It contains editable Java payload source, a C++ launcher and JNI/JVMTI bridge, recovered resources and mappings, pinned build dependencies, and a build script.

The input identifies itself as **Slinky Semantic 0.5.0-settings**. Its readable class names and reconstruction metadata made source recovery unusually direct. This is a reconstruction of that particular artifact; it does not establish vendor provenance or recover the author's original comments, formatting, or project files.

## Structure

| Path | Purpose |
|---|---|
| `java/src/main/java/dev/slinky/module/impl/` | Cheat module implementations |
| `java/src/main/java/dev/slinky/client/` | Game hooks, rendering, packet handling, input and profile UI |
| `java/src/main/java/dev/slinky/config/` | JSON configuration/profile logic |
| `java/src/main/java/dev/slinky/inject/` | Bootstrap, mappings and ASM class transformations |
| `java/src/main/java/dev/slinky/shaded/asm/` | Recovered bundled ASM source |
| `java/src/main/resources/` | Byte-for-byte recovered JSON/assets/fonts and licenses |
| `java/mappings/`, `mappings/` | Recovered namespace mappings |
| `native/launcher.cpp` | Process picker, DLL extraction and remote LoadLibraryW injection |
| `native/payload.cpp` | JVM attachment, payload loading and JNI/JVMTI native bridge |
| `tools/` | Static remapper, dependency restoration and verification utilities |
| `deps/` | Hash-pinned game/library metadata used during compilation |
| `dist/` | Source-built payloads and the launcher/DLL tested against Lunar |
| `evidence/` | REA/IDA evidence, provenance, build checks and runtime log |

## Build

Requirements: Windows x64, JDK 21 or another JDK supporting `--release 8`, CMake, and a complete Visual Studio C++ toolchain with Windows SDK. The tested toolchain was JDK 21.0.9, CMake 4.2.3, Visual Studio 18 2026/MSVC 19.50, and Windows SDK 10.0.26100.0. The script detects complete VS 2019/2022/2026 installations; CMake must support the selected generator.

Run from PowerShell:

```powershell
.\build.ps1
```

The included pinned dependencies allow an offline build. If dependencies have been removed, restore them with:

```powershell
.\build.ps1 -RestoreDependencies
```

Useful options:

```powershell
.\build.ps1 -JavaOnly
.\build.ps1 -JavaHome 'C:\path\to\jdk' -Generator 'Visual Studio 17 2022'
python .\tools\verify-build.py
```

The build script only invokes build tools. It does not launch or inject the reconstructed program. It:

1. Verifies all 34 pinned dependency files.
2. Compiles a separate trusted-ASM mapping tool and derives a named Minecraft 1.8.9 compilation dependency from the official client using recovered maps.
3. Compiles all 268 recovered Java source files to Java 8 classfiles.
4. Packages resource 101 (named), then statically maps it into 102 (SRG) and 103 (official), adding the correct runtime mapping resource to each.
5. Builds `SlinkyNative.dll` containing those three newly built JARs, then `Slinky.exe` containing the newly built DLL.

Outputs are `build/payload-101.jar`, `build/payload-102.jar`, `build/payload-103.jar`, and `build/native/Release/Slinky.exe` plus `SlinkyNative.dll`. No original application bytecode is used as a compilation fallback. Minecraft and its libraries are compilation dependencies and are not bundled into the cheat payloads.

The named game dependency adds the checked-exception declarations for five GuiScreen methods that Mojang stripped from shipping bytecode. That restores the development API needed by the recovered source without changing game implementations. See `deps/README.txt`.

## What was recovered and verified

Java source was recovered with Vineflower and CFR; the shaded ASM source was recovered too. The few compilation repairs are recorded in `java/SOURCE-REPAIRS.txt`: synthetic string-concatenation helpers, a local-variable scope correction, and moving illegal decompiler `strictfp` constructor modifiers to class declarations. There are no placeholder method bodies or omitted application classes.

Source recompilation produced **all 509 original class names**, with no missing or added classes and no differences in public, protected, or package-visible non-synthetic member names/descriptors. Compiler-generated private/synthetic implementation details can differ. See `java/STRUCTURAL-COMPARISON.json` and `evidence/build-verification.json`.

## Final bytecode review and corrections (10 October 2026)

The final review found that the first decompiled source was **not functionally identical**. It corrected 17 methods with arithmetic grouping or lost-temporary defects, including negative-number rounding, Instant Stop's direction choice, and movement/rendering calculations. It also restored the original evaluation order in a rendering method, the original array component type in `CoreModules.sourceOnlyIntervals`, and floating-point flags on twelve default constructors. See `evidence/final-java-review.txt` and the per-method audit records.

The updated build script verifies pinned dependencies, derives the named game dependency, handles dependency paths inside compiler argument files, restores manifests after remapping, and detects the available Visual Studio toolchain. These repairs make a fresh build follow the documented process.

All three final payloads contain the same 509 class names and field metadata as their originals, with all original assets/mappings unchanged. Every one of the 3,815 original methods per variant was compared. Of these, 3,132 bodies match directly (including uniquely matched generated helper bodies), and 511 pass conservative instruction-flow comparison. The remaining 172 require the separately recorded scope-aware comparisons and manual static review. This is thorough static verification, not a formal proof of complete equivalence for every possible environment, malformed input, reflection access or concurrent external modification.

To repeat the static comparison against the exact original specimen:

```powershell
python .\tools\verify-java.py 'C:\path\to\original\Slinky.exe'
```

This checks the original specimen's SHA-256, extracts its JARs as data, and uses independent pinned ASM to inspect both originals and the fresh build. It does not load or execute the inspected classes. The output records body comparisons, method flags/signatures, added generated methods, field metadata, manifests and resource equality.

The remapper was independently checked against the original artifacts as static comparison data: remapping the original named input reproduced all **3,815 method instruction streams** in each original SRG/official variant. That validates the remapping procedure; it does not claim every recompiled Java method is instruction-identical to the original. All rebuilt game-member references resolve. See `evidence/remap-validation.json`.

The native C++ is a behavioral reconstruction from IDA/Hex-Rays and raw-machine-code checks, with explicit JNI/JVMTI types. Its defensive error/reference cleanup differs from the original. Function mappings, decompiler corrections and deliberate differences are documented in `native/NATIVE-RECOVERY.txt` and `native/INDEPENDENT-STATIC-REVIEW.txt`.

Static checks verified the complete newly built resource chain: EXE resource 200 equals the built DLL; DLL resources 101–103 equal the newly built JARs. All three JARs preserve recovered assets, correct manifests/mappings and Java 8 class format.

## Authorized Lunar test

After the user explicitly authorized testing the reconstructed version, its launcher was run once against the user's already-running **Lunar Client 1.8.9 (7c5e05c/dev)**. The original sample remained unexecuted.

Observed log:

```text
Runtime: Lunar+OptiFine (named names)
Slinky Semantic 0.5.0-settings initialized with 66 modules
Patched 14 classes (0 failed, 2 warnings)
Slinky is running
```

Lunar remained running. The user confirmed that Right Shift opened the menu and that menu rendering/input worked. The two warnings concern extra renderer/input-loop hook locations in Lunar: 10 rather than 6 view-angle writes, and 6 rather than 2 input-loop redirects. They did not block initialization. Full gameplay-module behavior, all other clients/namespaces, and exact behavioral equivalence have not been runtime-tested.

The runtime evidence records the precise previously tested launcher hash. Logs are in `evidence/runtime-log.txt`, and scope/results in `evidence/runtime-test.json`. Those logs describe the earlier build. The updated binaries in `dist/` include the final source corrections and passed the static/build checks; they have not been launched or tested in Lunar again during this final review.

## Profiles

Profiles are plain JSON under Java's `user.home/.slinky/profiles/`. On this machine that is `C:\Users\GigaZelensky\.slinky\profiles\`. Filenames need not end in `.json`; the contents are JSON. Global settings are saved to `.slinky/slinky-semantic.json`, and active gameplay settings to `.slinky/slinky-semantic.json.active` (also JSON).

Illustrative profile record:

```json
{
  "Reach": {
    "__on": true,
    "__bind": 0,
    "__hold": false,
    "distance": 3.2,
    "require_sprint": false
  }
}
```

Module display names are top-level keys. `__on` enables a module, `__bind` is an ID from the bundled KeyNames table (not a Windows virtual-key code), and `__hold` enables hold binding behavior. The serializer omits disabled/zero/false control fields where appropriate. Numbers and booleans are JSON primitives; choices are label strings; ranges are `{ "min": ..., "max": ... }`; colors are `{ "r": ..., "g": ..., "b": ... }` with channels from 0 to 1; lists are JSON arrays. Profile key bindings live in the global `profile_binds` array.

`/slinky import` imports clipboard JSON or a user-specified profile file. Imported missing settings revert to the saved initial settings; it is configuration parsing, not script execution. Profile load/save/delete are implemented by `ProfileServices.FilesHost`, `ConfigStore`, `RecoveredConfiguration`, and `SettingCodec`.

## Limits

This is buildable recovered/reconstructed source with a successful Lunar initialization/menu test. It is not byte-identical original source, and compilation plus signature agreement is not a proof of complete runtime equivalence. The earlier malware assessment remains tied to the original specimen hash; the reconstructed native code and build tools are separately visible here. Original binaries were used only as static evidence and comparison inputs, never to fill missing compiled application classes.

Bundled fonts retain their recovered licenses. Game libraries retain their own notices in the pinned dependency JARs. No additional license or authorship for the recovered application is inferred from decompilation.
