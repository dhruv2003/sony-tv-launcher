# Home — Apple TV–style launcher for Android TV / Google TV

A small (Java, plain Views, no AndroidX) home screen for Android TV / Google TV, driven entirely
by the D-pad remote. Built and tested on a Sony Bravia KD-55X80K (Google TV, Android 12).

- **Top shelf hero** (top ~55% of the screen): the focused tile's art shown large, a blurred,
  darkened copy as the full-screen background, and the name in big type. It crossfades (~250 ms)
  as focus moves. Continue Watching items show their poster, title and "Resume".
- **Continue watching** row (from the system Watch Next list; hidden if empty or not readable).
- **Top row**: large rounded tiles using each app's TV banner (icon on a colour card if the app
  has none). The focused tile scales to 1.1x with a soft glow and shows its name underneath.
  It follows what you use: opening an app moves it to the front and the row keeps the latest four.
  Until then it starts from the defaults (`res/values/config.xml`): ZEE5, YouTube, Prime Video, JioHotstar, Sony LIV, Apple TV,
  Crunchyroll, MUBI, MX Player (installed ones only), then **Inputs** (`com.sony.dtv.tvx`, if present) and
  **Settings** (opens the quick settings panel).
- **All apps**: every other leanback app, most recently opened first (apps never opened from the
  launcher follow A-Z). Only launches from the launcher's tiles are counted.
- **Long-press OK** on an app: Move left / Move right / Remove from (or Add to) top row / App info.
  The order is saved in SharedPreferences.
- **Quick settings panel**: press **MENU** on the remote or select the gear tile. Wi‑Fi,
  Bluetooth, Sound output, Picture, Sleep timer, Inputs, All settings (each hidden if the TV
  doesn't provide it), plus launcher options: focus sounds on/off, **Continue watching** on/off,
  **Top row** on/off, **Hide both / Show both** and reset top row. Turning the top row off removes the
  dock band, the shelf above it and Continue Watching; all apps stay as one grid. Back closes it.
- **Focus sounds**: a quiet tick on focus moves and a click on OK (toggle in the panel).
- Back on the home screen does nothing; Home while already home jumps to the first tile.
- Refreshes when apps are installed/removed/updated and on every return to the home screen.
- Permissions: `INTERNET` is used only to download Continue Watching poster images.
  No analytics, no ads, no other network use.

HOME activity: **`dev.dhruv.tvhome/.MainActivity`**

## Install / activate

From the repo root (see `../CLAUDE.md` for the full walkthrough):

```sh
scripts/install-launcher.sh      # latest release (or: scripts/install-launcher.sh my.apk)
scripts/tv-home.sh custom        # make it Home
scripts/tv-home.sh stock         # back to the stock home screen
```

### Continue watching permission

The Watch Next list belongs to the system TV provider. Try granting read access:

```sh
adb shell pm grant dev.dhruv.tvhome com.android.providers.tv.permission.READ_EPG_DATA
```

If that fails with "not a changeable permission type" (or similar), Android 12 doesn't let a
sideloaded app hold it, and the row simply stays hidden. Even when granted, some TV providers
only return programs published by the calling app, so the row may still be empty — that's
expected, nothing else is affected.

## Signing

Debug and release builds are signed with `launcher/debug.keystore`, which is committed on
purpose (it's a throwaway key, password `android`). That way every build, local or CI, installs
over the previous one with `adb install -r`. If you want your own key, put it in
`launcher/keystore.properties` (git-ignored, format below) or in the repository secrets listed
under CI. Builds signed with a different key need `adb uninstall dev.dhruv.tvhome` first, which
resets the saved tile order.

## Build

Requirements: JDK 17+, Android SDK with platform 34 (`ANDROID_HOME` set or `local.properties`
with `sdk.dir=...`).

```sh
cd launcher
./gradlew lint assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew lint assembleRelease    # signed with keystore.properties if present, else the debug key
```

`launcher/keystore.properties` (git-ignored):

```properties
storeFile=/absolute/path/tv-home.jks
storePassword=...
keyAlias=tvhome
keyPassword=...
```

Create a key: `keytool -genkeypair -keystore tv-home.jks -alias tvhome -keyalg RSA -keysize 3072 -validity 10000`.

## CI

- `.github/workflows/launcher.yml`: on pushes touching `launcher/**`, runs `lint`,
  `assembleDebug` and `assembleRelease`, and uploads `tv-home-debug`, `tv-home-release` and the
  lint report as workflow artifacts.
- `.github/workflows/launcher-release.yml`: when a release tagged `launcher-v*` is published
  (`gh release create launcher-vX.Y.Z --generate-notes`), builds the release APK and attaches
  `tv-home-vX.Y.Z.apk` and `tv-home.apk`. It signs with your own key if the secrets
  `LAUNCHER_KEYSTORE_B64`, `LAUNCHER_KEYSTORE_PASSWORD`, `LAUNCHER_KEY_ALIAS` and
  `LAUNCHER_KEY_PASSWORD` exist, otherwise with the debug key.
