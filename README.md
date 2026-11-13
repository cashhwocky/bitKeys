# bitKeys

bitDroid's Keyboard App. It's a rounded-key keyboard that defaults to AMOLED black, with a flowing ribbon animation for voice typing.

## Features

- **Rounded keys on true black.** Pill-shaped space, mode and enter keys. Themes: **AMOLED** (default), Graphite, Light and Wallpaper colors (Material You).
- **Accent colors.** Lilac (default), Blue, Mint, Coral, Lavender, or your wallpaper's accent.
- **Key shapes.** Square (default), Rounded or Pill. Optional key borders and number row.
- **Voice typing.** Three light ribbons swell with your voice. Dictation runs continuously, with live partial results, pause/resume and delete-last-word. It uses the on-device recognizer when the phone has one (Android 13+).
- **Autocorrect.** Fixes typos when you press space or punctuation ("teh" → "the", "recieve" → "receive", "dont" → "don't", "i" → "I"), using a 37k-word dictionary and keyboard-aware spelling distance. Backspace right after a correction undoes it and remembers your word. Leaves texting words (lol, omg, idk…) alone, and can be turned off in settings.
- **Suggestions.** A strip shows what you typed, the best match and a second option. It learns words you type (stored on the device only, never in password or incognito fields).
- **Typing extras.** Multi-touch rollover, long-press the top row for digits, double-tap shift for caps lock, double space for ". ", and swipe the space bar to move the cursor.
- **Every emoji.** All 1,900+ Unicode emoji (Emoji 18.0) in categories, with recents and skin tones on long-press. Emoji the phone's font can't draw are hidden automatically.
- **Toolbar.** Emoji, paste from clipboard, settings, mic.
- **Works on the lock screen after a reboot** (direct-boot aware), so you can type a password before the first unlock. Learned words stay in encrypted storage until unlock.
- **Auto-capitalization** can be turned off in settings.
- **Number pad.** A big phone-style pad opens automatically in number and phone fields (you can turn this off in settings), or tap **1234** on the symbols page.
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

## Emoji data

`app/src/main/assets/emoji.txt` is generated from Unicode's `emoji-test.txt` with `tools/gen_emoji.py`. To update it for a new Unicode release, run:

```sh
python3 tools/gen_emoji.py emoji-test.txt > app/src/main/assets/emoji.txt
```

## Dictionary

`app/src/main/assets/words.txt` is built by `tools/gen_words.py` from:
- [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave (English 50k list), licensed **CC BY-SA 4.0**. The derived `words.txt` is shared under the same license.
- The Hunspell `en_US` dictionary, used to keep only correctly spelled words and restore apostrophes and capitals.

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
| `EmojiPanelView.kt` | Full emoji keyboard (categories, recents, skin tones) |
| `Storage.kt` | Device-protected settings storage for direct boot |
| `Suggester.kt` | Word predictions and learning |
| `Icons.kt` | Vector icons drawn on a 24×24 grid |

---

Made by Alex Desrosier · bitDroid
