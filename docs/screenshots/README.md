# Screenshots

> ⚠️ **Screenshots are never committed to this repo.**
>
> The image files in this folder are **local only** — `.gitignore` excludes
> everything here except this README. Keep phone screenshots for looking at and
> discussing, then let them stay on your machine. Do not add them to git.

Taken on the development device — Redmi Note 13 Pro+, Android 16, arm64.

## What was captured on 2026-07-28

| Screenshot | What it showed |
|---|---|
| First build on the phone | **The milestone.** Warp compiling an Android app on the phone: all 8 stages green, 756 KB signed APK, 35 s. The toolchain had installed itself from inside the APK. |
| Keyboard bug | The chat layout breaking when the keyboard opened — content drawn over the status bar. Cause: the activity had no `windowSoftInputMode="adjustResize"`, and the bottom inset was being padded twice. |
| Chat mirrored | The chat mirrored on a Hebrew phone. Tabs reversed and bubbles swapped sides, because Android mirrors the layout while every label in Warp is English. |

Both bugs were found only by running on a real phone in a real language. The
descriptions live here so the reasoning survives even though the images do not.
