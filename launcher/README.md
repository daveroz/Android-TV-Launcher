# Deco Launcher

A lightweight home screen for the Chromecast with Google TV and other Android TV
devices. No ads, no sponsored rows, no accounts. The only thing it fetches from the internet
is public show info and artwork from TVmaze (see [Show artwork](#show-artwork)), which you can
turn off. The APK is about 390 KB.

![Deco Launcher home screen](docs/preview-home.png)

*Previews rendered with made-up apps. On your TV, each card and the hero show the app's own banner.*

## Design

- **Layout after the Apple TV app.** The focused app fills the screen as the hero: its art on
  the right fading into the background, a big title on the left, and a blurred copy tinting the
  whole screen in the app's colours. A pill of tabs sits at the top with the clock on the right.
  Rows of cards run along the bottom, and the next row peeks in. Moving down scrolls the rows up
  and fades the hero away.
- **Look from Worldly.** Graphite surfaces (`#0B1322`, `#11141E`, `#1C2230`), thin slate borders
  (`#252C3C`), and one amber accent (`#DF8B35`) for focus rings, the focused tab and menu rows
  (as Worldly's amber gradient) and the active row title. The type is Worldly's Carbon+:
  regular for text, bold for titles and the clock. Corner radii are 12 for cards and 28 for menus.

| Apps page | App menu |
|---|---|
| ![Apps page](docs/preview-apps.png) | ![App menu](docs/preview-menu.png) |

### Icon

<img src="docs/icon.png" width="128" alt="Deco Launcher icon">

Part of the same icon family as Worldly and SAB Monitor: the graphite rounded square with the
slanted slate street-map grid, vignette and thin border, and a dark disc with an amber ring.
Deco's emblem is an amber TV showing a grid of app tiles. The focused tile is amber with the
family's white ring, and the cyan, green and yellow dots are the family's accent dots.

- It's an adaptive icon, so it works in any shape the system uses (circle, rounded square), and it
  has a one-colour layer for Android's themed icons.
- The TV banner (what Google TV shows in its apps row) uses the same art with "Deco" in Carbon+.
- The sources are the SVGs in [`assets/`](assets). To redraw the PNG/WebP files after editing
  them, run `node assets/render-icons.js && python3 assets/to-webp.py` (needs Playwright with
  Chromium, and Pillow).

## What it does

- **Home tab:** a **Now playing** row while music plays (see [Now playing](#now-playing)), a
  **Favourites** row (once you add some), then **TV apps** and **Other apps** (phone apps)
- **Streaming apps show their shows:** focus Netflix, Disney+, Prime Video, HBO Max, Hulu,
  Apple TV, Paramount+ or Peacock and the hero fills with that service's shows, changing every
  8 seconds (see [Show artwork](#show-artwork))
- **Apps tab:** every app in a 5-column grid. The order you set here is used everywhere.
- **Hold OK on a card** (or press Menu on remotes that have one):
  - **Add to / Remove from Favourites**
  - **Move** (Apps tab): use the arrows to slide the card, then press OK
  - **Hide**, **App info**, **Uninstall**
- **⚙** opens Android TV settings. **⋮** has hidden apps, Now playing setup, show artwork on/off
  and Home button setup.
- **Up** from the top row, or **Back** from anywhere, goes to the tabs. Back on the Apps tab goes
  to Home. Focus coming up from the cards always lands on the current tab.
- **Home** while already home jumps back to the top.
- **Play/pause** on the remote controls what's playing.
- **Updates by itself** when apps are installed, removed, updated or disabled.

## Now playing

While Spotify (or YouTube Music, a podcast app, any app with standard media controls) is playing
or paused, Home starts with a **Now playing** row: cover, track, artist, previous / play-pause /
next and a progress bar. OK on the track opens the player; the hero shows the cover.

Android only shows other apps' media to apps with notification access, and Android TV has no
screen to turn that on, so do it once from Termux:

```bash
adb shell cmd notification allow_listener com.camdeco.launcher/com.camdeco.launcher.MediaListenerService
```

Go back to Home and start some music. **⋮ → Now playing** shows whether access is on. To turn it
off, run the same command with `disallow_listener`.

## Show artwork

Netflix, Disney+ and the other services only share their own recommendations with Google's
built-in launcher. So instead, Deco Launcher uses [TVmaze](https://www.tvmaze.com)'s free public
API (no account or key): twice a day it fetches the past week's new streaming episodes, keeps each
service's most popular shows, and downloads their backgrounds once (shrunk to TV size, about 40 MB
of cache at most). The hero then shows the show's name, genres, latest episode and summary, with
"Show info from TVmaze" as credit (TVmaze's data is CC BY-SA).

It covers TV shows with new episodes that week, not films. Turn it off with **⋮ → Show artwork**;
then streaming apps show their own banner like every other app and nothing is downloaded.

## Install

From Termux on your phone (see the main README for setting up `adb`):

```bash
curl -LO https://github.com/daveroz/Cam-deco/releases/download/launcher-latest/DecoLauncher.apk
adb connect 192.168.x.x:5555
adb install -r DecoLauncher.apk
```

That link always points to the latest build from GitHub Actions (see [Building](#building)).

Then open **Deco Launcher** from the Google TV apps list to try it out.

## Make the Home button open it

On a Chromecast, Google TV keeps the Home button for itself. There are two ways
around that. Try the launcher for a while before using either.

### Option 1: Home button override (keeps Google TV installed)

On the TV, go to **Settings → System → Accessibility → Deco Launcher Home button**
and turn it on. If the switch is greyed out, run this instead:

```bash
adb shell settings put secure enabled_accessibility_services com.camdeco.launcher/com.camdeco.launcher.HomeButtonService
adb shell settings put secure accessibility_enabled 1
```

The first command replaces the list of enabled accessibility services. Any other
accessibility service you had on, such as TalkBack, gets turned off.

Each time Google TV's home screen appears, Deco Launcher opens on top of it, so
Google TV may flash for a moment. Panels that Google TV itself opens (from its
home screen app) will also bounce back to Deco Launcher. The service only
watches Google TV's windows and doesn't read anything on screen.

### Option 2: Disable Google TV (frees the most memory)

The HD model only has 1.5 GB of RAM, and Google TV's home screen is the biggest
thing running on it. Disabling it gives that memory back:

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Deco Launcher then becomes the Home app. To undo:

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

**Keep the undo commands.** If Deco Launcher is ever uninstalled while Google TV is
disabled, the Home button does nothing until you run them. With Google TV disabled,
its own features (profiles, the For You tab, recommendations) are gone. Casting and
voice search are separate apps and should keep working.

## Updating

Download the latest APK again and install it over the old one with `adb install -r DecoLauncher.apk`.
Favourites, hidden apps and the order are kept.

Android only installs an update that is signed with the same key as the version already on the TV.
GitHub Actions signs every build with the key stored in the `DECO_KEYSTORE_BASE64` secret. That key
is the one that signed the earlier builds, so the builds install over each other. If you ever see a
signature error, run `adb uninstall com.camdeco.launcher` first; that resets favourites, hidden apps
and the order.

## Building

### On GitHub

[`.github/workflows/build.yml`](../.github/workflows/build.yml) runs on every push that changes
`launcher/`, and from **Actions → Build APK → Run workflow**. It runs the tests, builds the release
APK, uploads it as the **DecoLauncher.apk** artifact and, on the default branch, publishes it to the
[`launcher-latest`](https://github.com/daveroz/Cam-deco/releases/tag/launcher-latest) release. The
run's summary shows the version and the signing key's SHA-256 fingerprint.

The signing key lives in one repository secret: **Settings → Secrets and variables → Actions →
New repository secret**, named `DECO_KEYSTORE_BASE64`, holding the keystore file encoded with
`base64 -w0`. It's a standard debug-style keystore (password `android`, alias `androiddebugkey`);
for a different keystore, also add `DECO_KEYSTORE_PASSWORD`, `DECO_KEY_ALIAS` and
`DECO_KEY_PASSWORD`. Without the secret, builds still run but are signed with a throwaway key and
aren't published to the release.

### On your own computer

Needs JDK 21 and the Android SDK with platform 36.

```bash
./gradlew testReleaseUnitTest    # Robolectric tests that drive the launcher with remote key presses
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

To sign with the fixed key, point `DECO_KEYSTORE_FILE` at the keystore file first; otherwise the
build uses your computer's debug key.
