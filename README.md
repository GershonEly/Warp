<div align="center">

# ⚡ Warp

**An AI coding IDE for Android that compiles Android apps — on your phone.**

Talk to Claude, Gemini, or GPT. It writes the code, draws the icons,
compiles the APK, and installs it.

*No computer. No build server. 100 % Kotlin.*

</div>

---

## 🔑 Warp ships with **no API key**

You bring your own. It is stored encrypted in Android Keystore on your device
and never leaves it.

**There is no key anywhere in this repository, and there never will be.**

---

## ✨ What it does

| | |
|---|---|
| 🏗 **Antigravity feature parity** | Agent Manager, artifacts, plans, tools, skills, MCP |
| 🤖 **All the AIs** | Claude · Gemini · GPT — pick any model, switch any time |
| 🧠 **Already knows Android** | Never explain icon sizes, folders, or rules again |
| 🎨 **Draws its own images** | Icons, splash screens — all densities, right folders |
| 📦 **Compiles on-device** | Real Kotlin → real APK → installed, all on the phone |

---

## 🤔 How can it compile on a phone?

Android blocks apps from running programs they downloaded — and a compiler is a
program. But that rule only applies to apps targeting **Android 10 or newer**.

Warp targets **Android 9** (`targetSdk 28`), so the rule does not apply.

```kotlin
compileSdk = 36   // all modern APIs, Compose, Material 3
targetSdk  = 28   // permission to run our own toolchain
```

This is exactly what [Termux](https://termux.dev) does, and it has worked for a
decade. [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE) already
runs a full JDK and the Kotlin compiler on-device — so we know it works.

---

## 📦 Install

```bash
adb install --bypass-low-target-sdk-block warp.apk
```

The `--bypass-low-target-sdk-block` flag is needed on Android 14+.

---

## 🛠 Build from source

**Requirements**

- JDK 17 or newer
- Android SDK with platform `android-36`
- ~2 GB free disk space

```bash
git clone https://github.com/<you>/warp.git
cd warp
./gradlew assembleDebug
```

The build automatically downloads the on-device toolchain (~350 MB) from GitHub
Releases and packs it into the APK. **The toolchain is not stored in this repo** —
GitHub rejects files over 100 MB.

### Google Sign-In (optional)

Sign-in is used for identity only, not for AI access. Because Android OAuth
clients are bound to a signing certificate, you must register your own:

1. Create an **OAuth 2.0 Client ID** (type: *Android*) in Google Cloud Console
2. Package name: `dev.ely.warp`
3. SHA-1: run `./gradlew signingReport` and copy the debug fingerprint

Warp runs fine without this — you just won't see your Google profile.

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

---

## 📱 Requirements

| | |
|---|---|
| Android | 10+ *(API 29)* |
| Architecture | arm64 |
| RAM | 6 GB minimum, 8 GB+ recommended |
| Storage | ~1 GB for Warp and its toolchain |

Tested on Xiaomi Redmi Note 13 Pro+ and recent Samsung devices.

> **Xiaomi / Samsung:** these skins kill background apps aggressively. Warp will
> ask once for a battery-optimisation exemption so builds are not interrupted.

---

## 📖 Documentation

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) — full design and roadmap
- [`PROGRESS.md`](PROGRESS.md) — current state of the build

---

## 📄 License

[MIT](LICENSE)
