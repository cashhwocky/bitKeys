# bitKeys

bitDroid's Keyboard App. It's a rounded-key keyboard that defaults to AMOLED black, with a flowing ribbon animation for voice typing.

## Features

- **Rounded keys on true black.** Pill-shaped space, mode and enter keys. Themes: **AMOLED** (default), Graphite, Light and Wallpaper colors (Material You).
- **Accent colors.** Mint (default), Blue, Coral, Lavender, or your wallpaper's accent.
- **Key shapes.** Rounded, Pill or Square. Optional key borders and number row.
- **Voice typing.** Three light ribbons swell with your voice. Dictation runs continuously, with live partial results, pause/resume and delete-last-word. It uses the on-device recognizer when the phone has one (Android 13+).
- **Suggestions.** A strip shows what you typed, the best match and a second option. It learns words you type (stored on the device only, never in password or incognito fields).
- **Typing extras.** Multi-touch rollover, long-press the top row for digits, double-tap shift for caps lock, double space for ". ", and swipe the space bar to move the cursor.
- **Toolbar.** Emoji panel, paste from clipboard, settings, mic.
- **No dependencies.** Pure Kotlin and the Android SDK, about a 100 KB APK.

## Install

Grab `bitKeys-x.y.z-release.apk` from [Releases](../../releases), or the latest build from the **Actions** tab. Then open **bitKeys** and follow the three setup steps: turn it on, switch to it, and allow the mic.

## Build

```sh
./gradlew assembleRelease
```

Requires JDK 17 and the Android SDK (compileSdk 35, minSdk 31).

Every push to `main` builds debug and release APKs in GitHub Actions. Pushing a tag like `v0.1.0` publishes a GitHub Release with the APK attached.

To sign releases with your own key, add these repo secrets: `BITKEYS_KEYSTORE_B64`, `BITKEYS_KEYSTORE_PASSWORD`, `BITKEYS_KEY_ALIAS` and `BITKEYS_KEY_PASSWORD`. Without them, the release APK is debug-signed.

## Ship it in bitDroid

See [`rom/Android.bp`](rom/Android.bp). Put it with `bitKeys.apk` in your tree and add `PRODUCT_PACKAGES += bitKeys`. Its `overrides: ["LatinIME"]` removes the AOSP keyboard so bitKeys is the default on first boot.

## Project layout

| File | What it does |
| --- | --- |
| `BitKeysService.kt` | The input method: text commits, enter actions, suggestions, voice recognition |
| `KeyboardView.kt` | Draws and handles the whole keyboard (strip + keys) |
| `VoicePanelView.kt` | Voice-typing panel and ribbon animation |
| `SettingsActivity.kt` | Setup steps, themes, accent, key shape, toggles |
| `Theme.kt` | Preferences and color palettes |
| `Suggester.kt` | Word predictions and learning |
| `Icons.kt` | Vector icons drawn on a 24×24 grid |

---

Made by Alex Desrosier · bitDroid
