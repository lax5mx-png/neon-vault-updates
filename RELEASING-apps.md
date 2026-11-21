# Releasing Simmer, Puzzlebox and DealScout

Each folder (`simmer/`, `puzzlebox/`, `dealscout/`) has:

- `update.json`, which the app downloads every 6 hours while open
- `index.html`, the whole app UI as one file (a "content" update)
- `<App>-<version>.apk`, the full app (an "APK" update)

## Content-only update (most changes)
1. Build the new `index.html`.
2. Put it in the folder and raise `content.v` by 1 in `update.json`, with its new `sha256`.
3. Keep `content.minVc` at the lowest app versionCode that can run it.
The app swaps in the new UI silently. If the new UI doesn't start within 12 seconds it rolls back.

## APK update (native changes)
1. Raise `versionCode` and `versionName`, then build and sign with the **same keystore** as before.
2. Replace the APK in the folder and set `apk.vc`, `apk.vn`, `apk.url`, `apk.sha256` and `apk.size`.
3. Add a short `notes` line. The app shows it after updating.
Android asks once to allow installs from the app. After that, updates install when you leave the app.

Never commit keystores.
