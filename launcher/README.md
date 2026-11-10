# Deco Launcher

A lightweight home screen for the Chromecast with Google TV and other Android TV
devices. No ads, no sponsored rows, and no internet permission, so it can't send
anything anywhere. The APK is about 250 KB.

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

## What it does

- **Home tab:** a **Favourites** row (once you add some), then **TV apps** and **Other apps** (phone apps)
- **Apps tab:** every app in a 5-column grid. The order you set here is used everywhere.
- **Hold OK on a card** (or press Menu on remotes that have one):
  - **Add to / Remove from Favourites**
  - **Move** (Apps tab): use the arrows to slide the card, then press OK
  - **Hide**, **App info**, **Uninstall**
- **⚙** opens Android TV settings. **⋮** has hidden apps and Home button setup.
- **Back** goes up to the tabs, then from Apps to Home. Focus coming up from the cards always lands on the current tab.
- **Home** while already home jumps back to the first card.
- **Updates by itself** when apps are installed, removed, updated or disabled.

## Install

From Termux on your phone (see the main README for setting up `adb`):

```bash
curl -LO https://raw.githubusercontent.com/daveroz/Cam-deco/ccr-beaea807-gbu79w/releases/deco-launcher.apk
adb connect 192.168.x.x:5555
adb install -r deco-launcher.apk
```

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

Install a newer APK over the old one with `adb install -r deco-launcher.apk`.

Each build is signed on the machine that built it. If an update was built
somewhere else, Android refuses it with a signature error. Uninstall first with
`adb uninstall com.camdeco.launcher`, which resets the tile order and hidden apps.

## Building

Needs JDK 17+ and the Android SDK with platform 36.

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # Robolectric tests that drive the launcher with remote key presses
```
