# 🔧 The Warp toolchain

This folder holds the **scripts** that build Warp's on-device compiler toolchain.
It holds **no binaries** — those are fetched, checked, and assembled by a script,
then uploaded to GitHub Releases.

---

## What problem this solves

Warp compiles Android apps **on the phone**. To do that the phone needs a real
compiler toolchain: a Java runtime, the Kotlin compiler, and Google's resource
and dex tools — all built for **arm64 Android**.

Google does not ship those for Android. So we assemble them ourselves.

---

## How to build the bundle

```powershell
py toolchain/build_toolchain.py --sdk "C:\Users\ely\Android\Sdk"
```

Add `--strict` for a release build. That makes it fail if any source is missing
a pinned checksum.

Downloads are cached in `toolchain/.cache/`, so a second run is fast.

**Output:** `toolchain/build/warp-toolchain-arm64-<version>.zip` — about **171 MB**
(297 MB unpacked).

---

## What ends up in the bundle

```
warp-toolchain-arm64/
├── MANIFEST.json        versions + the env vars the launcher must set
├── bin/
│   ├── aapt2            compiles resources, links the APK
│   └── zipalign         aligns the final APK
├── jvm/                 OpenJDK 17 — run jvm/bin/java
├── lib/                 extra .so files the JVM needs
├── kotlinc/lib/*.jar    the Kotlin compiler (54 jars)
├── d8/r8.jar            .class → .dex
└── platform/android.jar the Android API surface, for the compile classpath
```

---

## Where each piece comes from

| Piece | Version | Source | Licence |
|---|---|---|---|
| `aapt2`, `zipalign` | 35.0.2 | [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) | Apache-2.0 |
| OpenJDK | 17.0.20 | Termux package repo | GPLv2 + Classpath Exception |
| `libandroid-shmem` | 0.7 | Termux | permissive |
| `libandroid-spawn` | 0.3 | Termux | permissive |
| `libiconv` | 1.18-1 | Termux | LGPL |
| `zlib` | 1.3.2 | Termux | zlib licence |
| `kotlinc` | 2.4.10 | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| `d8` (in `r8.jar`) | 9.1.31 | Google Maven | Apache-2.0 |
| `android.jar` | android-37.0 | **your local SDK** | Android SDK Terms |

Exact URLs and SHA-256 checksums are pinned in [`sources.json`](sources.json).

**`android.jar` is deliberately not downloaded.** The Android SDK licence
restricts redistribution, so the script copies it from the SDK already installed
on the build machine. That is why `--sdk` is required.

The JDK's `legal/` folder is **kept on purpose** so the licence texts ship
alongside the binaries.

---

## Two findings worth remembering

### 1. `aapt2` is statically linked — that makes it easy

The `aapt2` and `zipalign` builds we use are **static** AArch64 executables:

```
Class:   ELF64
Type:    EXEC
Machine: AArch64
(no INTERP segment)
```

No interpreter, no shared libraries. They run from any folder, with no
environment set up. Nothing to configure.

### 2. The JDK is built for Termux — but it still relocates

This one looked like a blocker at first.

**The problem:** Termux compiles its packages for one fixed location,
`/data/data/com.termux/files/usr`. Warp's folder is
`/data/data/dev.ely.warp/files`. The official Termux docs say packages "would
not work for any other app package name without being recompiled."

**Why it works anyway.** Inspecting all 62 binaries in the package showed:

- The program interpreter is **`/system/bin/linker64`** — Android's own linker,
  present on every device. Not a Termux-specific loader.
- **58 of 62** files already list `$ORIGIN` in their `RUNPATH`, meaning "look
  next to me". Those relocate by themselves.
- The remaining 4 — including `libjvm.so` — list only absolute Termux paths.
- But the linker searches **`LD_LIBRARY_PATH` before `RUNPATH`**. Setting that
  variable overrides the baked-in paths.

**The fix:** ship the 4 extra libraries in `lib/`, and have the launcher set

```
LD_LIBRARY_PATH=<toolchain>/jvm/lib:<toolchain>/jvm/lib/server:<toolchain>/lib
JAVA_HOME=<toolchain>/jvm
```

Only **3** genuinely system libraries are needed — `libc`, `libm`, `libdl` —
and Android always provides those.

The build script re-checks this every run. It scans every binary in the bundle
and reports any required library that is neither shipped nor provided by
Android. A clean run prints:

```
all native dependencies satisfied
```

---

## Reproducible on purpose

Every source is pinned by SHA-256, and the zip is written with fixed timestamps
and permissions in sorted order. Two builds from the same sources produce a
**byte-identical** archive — verified:

```
run 1: f0de043a00f1a616430155866d982ae70110b55921053d3216ed001823fc6322
run 2: f0de043a00f1a616430155866d982ae70110b55921053d3216ed001823fc6322
```

That matters because Warp is open source and sideloaded. Anyone can rebuild the
bundle and confirm it matches the one we published.

---

## ⚠️ What is NOT proven yet

Everything above was verified **on the PC**, by reading the binaries.

Still to confirm **on a real phone**:

- [ ] `jvm/bin/java -version` runs from Warp's data directory
- [ ] `kotlinc` compiles a `.kt` file to `.class`
- [ ] `aapt2` compiles and links resources
- [ ] `d8` produces a `.dex`
- [ ] How long a small app takes to build, and how much RAM it needs

Until the JVM actually starts on the device, the relocation reasoning above is
**well-evidenced but unproven**.
