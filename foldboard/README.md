# FoldBoard

A fast, smart Android keyboard designed for foldable phones (built and tuned with the Galaxy Z Fold in mind).

- **Folded (cover screen):** one honeycomb keyboard, with the space key in the middle of the letters.
- **Unfolded (inner screen):** the keyboard splits automatically into two thumb clusters with an optional number/symbol panel in the middle.
- **Private:** the app has no internet permission. Typing, learning, clipboard history and on-device voice all stay on the phone.

## Install on your phone

1. Open the repo's **Releases** page on your phone and download the latest `FoldBoard-0.1.N.apk`
   (every push to `foldboard/` builds one through GitHub Actions: workflow **FoldBoard APK**).
2. Open the APK and allow "Install unknown apps" for your browser if asked.
3. Open **FoldBoard** from the app drawer and tap through **Get started**:
   turn it on, make it your keyboard, allow the microphone (only for voice typing).

## What's in it

| Area | Features |
|---|---|
| Layouts | Honeycomb (hex) or classic keys · QWERTY, QWERTZ, AZERTY, Colemak, Dvorak · auto-split when unfolded · number/symbol center panel · one-handed mode when folded · two symbol pages |
| Speed | Center space keys inside the letter clusters · two-thumb rolling multi-touch · swipe up = capital, swipe down = symbol/digit · swipe left = delete word, swipe right = restore it · slide the space key to move the cursor · long-press for accents · glide typing (beta, unsplit) |
| Smarts | Autocorrect that uses *where* you touched each key, not just the letter · undo any correction with one backspace · completions while typing · next-word prediction that learns your word pairs · learns your names/slang · `dont` → `don't`, `i` → `I`, accent restoration (`cafe` → `café`) · smart punctuation spacing · double-space period · auto-capitalization |
| Languages | English, Spanish, French, German, Italian, Portuguese dictionaries (60k / 35–40k words) |
| Voice | Dictation via the system recognizer, on-device when available · keeps listening across pauses · live text as you speak · spoken punctuation ("comma", "period", "new line") |
| Sizing & look | Separate height for folded and unfolded · split gap · key spacing · label size · bottom padding · dark, black (AMOLED), light or system theme · 6 accent colors · vibration and sound strength |
| Extras | Clipboard history with pinning and one-tap paste of the latest copy · emoji panel with recents · live keyboard preview in settings |

## Project layout

```
foldboard/
  core/   pure Kotlin engine, unit-tested on the JVM (no Android SDK needed)
          layout/   honeycomb + classic layout generator, hit testing
          text/     trie, touch-aware fuzzy search, autocorrect, prediction, learning, glide decoder, voice text
          gesture/  swipe classification
  app/    Android input method service, keyboard view, settings screen
  tools/  dictionary generator (wordfreq)
```

Build locally with Android Studio, or:

```
cd foldboard
./gradlew :core:test            # engine tests
./gradlew :app:assembleRelease  # APK in app/build/outputs/apk/release/
```

### Signing

Without secrets, CI signs with a cached debug key so new builds install over old ones.
For a permanent key, add these repository secrets: `FOLDBOARD_KEYSTORE_BASE64` (base64 of a `.jks`),
`FOLDBOARD_KEYSTORE_PASSWORD`, `FOLDBOARD_KEY_ALIAS`, `FOLDBOARD_KEY_PASSWORD`.
Switching keys requires uninstalling the old build once.

### Dictionaries

`tools/gen_dictionaries.py` regenerates `app/src/main/assets/dict/*.txt` from
[wordfreq](https://github.com/rspeer/wordfreq) (data CC BY-SA 4.0), filtering out common web misspellings.

## Roadmap

- Hinge-aware layouts for Flex mode (keyboard on the bottom half when half-folded) and tabletop posture
- Per-app layout/size memory, floating keyboard
- Multilingual typing without switching (two dictionaries at once), German noun capitalization
- Glide typing across split halves, smarter glide ranking with context
- Personal text shortcuts / snippets, emoji search and emoji suggestions while typing
- Themes with custom colors and backgrounds, key-height per row
- Accessibility (TalkBack key exploration)
- Optional on-device AI rewrite/grammar (local model) — still without network access
