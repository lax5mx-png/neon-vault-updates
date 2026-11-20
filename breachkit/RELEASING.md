# Breach Kit updates

The Android app (`com.breachkit.app`) checks `breachkit/update.json` on launch and every 30 minutes.

```json
{
 "content": {"v": 4, "url": ".../breachkit/index.html", "sha256": "..."},
 "apk":     {"vc": 4, "vn": "1.3", "url": ".../breachkit/BreachKit-1.3.apk", "sha256": "...", "size": 0},
 "notes": "Shown to the player after a game update applies."
}
```

## Game update (most changes: new jobs, fixes, balance) — silent
1. Edit `source/game.html` (the browser version of the game).
2. Build the app copy: `python3 breachkit/source/make_app_html.py breachkit/index.html` (swaps Google Fonts for the fonts bundled in the APK).
3. Bump `content.v` by 1, set `content.sha256` to the SHA-256 of the new `breachkit/index.html`, update `notes`.
4. Commit and push to `main`. Phones download it, verify the hash, and swap it in the next time no job is running. The save is untouched.
   Never break the bridge API the shell expects: `window.__back`, `window.__canReload`, `window.__toast`, `window.__apkReady`, and calling `BK.ready()` on boot. If a downloaded build never calls `BK.ready()` within 10 s, the app rolls back to the bundled game and skips that build number.

## App update (only when the Java shell changes)
1. Edit `source/src/...`, bump `versionCode` / `versionName` in `source/AndroidManifest.xml`. Set `assets/content_version.txt` to the current content build.
2. Build with `source/build.sh` (needs Android build-tools 34 + platform 34) and sign with the **same keystore** (`breachkit.keystore`, kept by the owner, never committed). A different key = Android refuses the update.
3. Upload `BreachKit-<vn>.apk`, set `apk.vc`, `apk.vn`, `apk.url`, `apk.sha256`, `apk.size`, push.
   First self-update asks the player to approve once; after that (Android 12+) updates install silently when the app is closed.
