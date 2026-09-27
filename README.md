<div align="center">

# ⚡ Warp

**An AI coding IDE for Android that compiles Android apps — on your phone.**

Talk to Claude, Gemini, or GPT. It writes the code, draws the icons,
compiles the APK, and installs it.

*No computer. No build server. 100 % Kotlin.*

</div>

---

## ✨ What it does

| | |
|---|---|
| 📦 **Compiles on-device** | Real Kotlin → real APK → installed and launched, all on the phone |
| 🔵 **Jetpack Compose, or XML** | Compose by default; XML if you ask. Both build on the phone |
| 🤖 **All the AIs** | Claude · Gemini · GPT — pick any model, switch mid-conversation |
| 🧠 **Already knows Android** | Icon sizes, resource folders, manifest rules — never explain them again |
| 🎨 **Draws its own icons** | Generated, then written at all five densities, adaptive and themed |
| 🔀 **Git, so there is an undo** | Save points, history, diffs and restore work with no account at all |
| 🔍 **Reads the app it built** | The files and database of your app, from inside the chat |
| 🎤 **Voice input** | Dictation with a live waveform, on-device where the phone supports it |
| 🌐 **Web search** | On by default, so it can look something up instead of guessing |

Also: a live task list that ticks itself off, a coloured diff for every change,
and rewind — edit a message and take the files back with it.

**Four slash commands**, and they earn their slash by being deterministic:
`/plan` plans without doing, `/goal` works until a condition is true, `/rules`
is what it must never do, `/grill-me` interrogates a plan one question at a
time. Everything else is a sentence.

**Permissions, by what a tool can cost you:** reading inside your project never
asks; writing asks once and offers *Always*; running asks every time; anything
that leaves the device asks per domain.

---

## 🤔 How can it compile on a phone?

Android blocks apps from running programs they downloaded — and a compiler is a
program. But that rule only applies to apps targeting **Android 10 or newer**.

Warp targets **Android 9** (`targetSdk 28`), so the rule does not apply.

```kotlin
compileSdk = 37   // all modern APIs, Compose, Material 3
targetSdk  = 28   // permission to run our own toolchain
minSdk     = 28   // cannot exceed targetSdk
```

This is exactly what [Termux](https://termux.dev) does, and it has worked for a
decade. [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE) already
runs a full JDK and the Kotlin compiler on-device — so we know it works.

**What actually runs, in order:** `aapt2` compiles the resources, `kotlinc`
2.4.10 compiles the Kotlin, `d8` dexes it, and `apksig` signs it. Compose needs
no extra compiler — its plugin ships inside kotlinc and is versioned with
Kotlin.

Measured on a Redmi Note 13 Pro+:

| | Build | Size |
|---|---|---|
| **XML app** | ~12 s | ~50 KB |
| **Compose app** | ~43 s | ~8 MB |

A Compose app carries the whole Compose runtime with it, which is why both
toolkits are kept: ask for Compose and get a modern app, ask for XML and get one
that builds in seconds and fits in 50 KB.

---

## 📦 Install

```bash
adb install --bypass-low-target-sdk-block warp.apk
```

The `--bypass-low-target-sdk-block` flag is needed on Android 14+.

The APK is **~285 MB**, because the entire toolchain travels inside it. That is
the point: once installed, Warp needs no download to build.

---

## 🛠 Build from source

**Requirements**

- JDK 17 or newer
- Android SDK with platform `android-37`
- ~4 GB free disk space (the toolchain, the APK and Gradle's caches)

```bash
git clone https://github.com/GershonEly/Warp.git
cd Warp
./gradlew assembleDebug
```

The build automatically downloads the on-device toolchain (**202 MB**) from
GitHub Releases and packs it into the APK. **The toolchain is not stored in this
repo** — GitHub rejects files over 100 MB.

---

## 🔌 Connecting an AI

Open **Settings → AI Provider** and choose one:

| Provider | Where to get a key | Notes |
|---|---|---|
| **OpenRouter** ⭐ | [openrouter.ai](https://openrouter.ai) | One key → Claude + Gemini + GPT |
| Anthropic | [console.anthropic.com](https://console.anthropic.com) | Claude only |
| Google | [aistudio.google.com](https://aistudio.google.com) | Gemini + image generation, free tier |
| OpenAI | [platform.openai.com](https://platform.openai.com) | GPT only |

Without a key, Warp runs in **🎭 Demo mode** on a built-in mock AI, so you can
explore every screen before spending anything.

**Pushing to GitHub** needs a token, added separately in **Settings → GitHub**;
it lives in the Keystore and is never shown back. Everything git does locally —
save points, history, diffs, restore — needs no account of any kind.

---

## 📱 Requirements

| | |
|---|---|
| Android | 9+ *(API 28)* |
| Architecture | arm64 |
| RAM | 6 GB minimum, 8 GB+ recommended |
| Storage | **400 MB free** to unpack the toolchain, on top of the app |

Warp checks for that 400 MB before it starts unpacking, and says so if it is
missing rather than failing halfway.

Tested on **Xiaomi Redmi Note 13 Pro+** and **Samsung SM-S942B**, both on
Android 16.

> **Xiaomi / Samsung:** these skins kill background apps aggressively. Warp will
> ask once for a battery-optimisation exemption so builds are not interrupted.

---

## 📖 Documentation

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) — full design and roadmap
- [`PROGRESS.md`](PROGRESS.md) — current state of the build

---

## 📄 License

[MIT](LICENSE)
