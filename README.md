# Cam-deco

Tools for a Chromecast with Google TV (HD, codename `boreal`, Android TV OS 14).

- **[Deco Launcher](launcher/README.md):** a lightweight, ad-free home screen with an Apple TV-style layout and Worldly's look. Built by GitHub Actions; the latest APK is always at [`releases/download/launcher-latest/DecoLauncher.apk`](https://github.com/daveroz/Cam-deco/releases/download/launcher-latest/DecoLauncher.apk).
- **[`scripts/chromecast-dump.sh`](#copy-the-firmware-files-off-the-chromecast):** copies the readable firmware files off the device.
- **[`scripts/disable-google-tv.sh`](#turn-off-the-google-tv-home-screen-safely):** turns off the Google TV home screen safely, checking that nothing else breaks.
- **[`scripts/chromecast-5ghz.sh`](#turn-off-google-tv-and-check-5-ghz-wifi):** turns off the Google TV home screen, then checks the TV can see and join your 5 GHz WiFi.

## Turn off the Google TV home screen safely

From Termux, on the same network as the TV:

```bash
curl -LO https://raw.githubusercontent.com/daveroz/Cam-deco/ccr-beaea807-gbu79w/scripts/disable-google-tv.sh
bash disable-google-tv.sh 192.168.x.x           # asks before changing anything
bash disable-google-tv.sh 192.168.x.x --undo    # Google TV back on
```

What it does:

1. **Checks first** and changes nothing if Deco Launcher isn't installed. It saves the list of apps
   already turned off on the TV to a file, and notes the WiFi network and internet.
2. **Makes Deco Launcher the Home app**, then turns off **only** the Google TV home screen
   (`com.google.android.apps.tv.launcherx`) with `pm disable-user`. Nothing is uninstalled or
   deleted. The Google TV setup app is left alone unless it grabs the Home button, and then only
   if you say yes.
3. **Presses Home** to check it opens Deco Launcher. If it doesn't, it **puts Google TV back by
   itself**, so the TV is never left without a working Home screen.
4. **Checks everything else afterwards:** WiFi on the same network and band, internet, Settings
   opens, and Google Play services, Play Store, casting, voice search and the keyboard are all
   still on. It also confirms no other app was turned off.

## Turn off Google TV and check 5 GHz WiFi

From Termux, on the same network as the TV:

```bash
curl -LO https://raw.githubusercontent.com/daveroz/Cam-deco/ccr-beaea807-gbu79w/scripts/chromecast-5ghz.sh
bash chromecast-5ghz.sh 192.168.x.x "Your 5 GHz WiFi name"      # add wpa3 or open if needed
bash chromecast-5ghz.sh 192.168.x.x --undo                      # Google TV home screen back on
```

It only turns Google TV off if Deco Launcher is installed. It then shows the current WiFi and
country code, scans for your network on 5 GHz (with its channel and signal), joins it (asking for
the password without showing it), and checks the internet works. If the TV can't see the network
on 5 GHz, it says what to change on the router. Joining another network can change the TV's
address; if so, the script tells you how to reconnect.

## Copy the firmware files off the Chromecast

`scripts/chromecast-dump.sh` copies everything readable from the Chromecast over
WiFi using ADB. No root is needed. It saves:

- **Device info:** build properties, kernel version, partition table, mounts and the package list
- **System file trees:** `/system`, `/vendor` and the other system partitions, as `.tar` archives (roughly 1–3 GB)
- **App APKs:** apps you installed, plus updated versions of Google's apps
- **`SHA256SUMS`:** a checksum for every file it saved

Without root, the boot, kernel, bootloader and other raw partition images can't be
read. That makes this a reference copy for inspection, **not a flashable image**,
so it can't restore a bricked device.

### On the Chromecast

1. Go to **Settings → System → About** and select **Android TV OS build** 7 times.
2. Go to **Settings → System → Developer options** and turn on **USB debugging**.
   If **Wireless debugging** is there, turn it on too.
3. Note the IP address under **Settings → Network & Internet** → your WiFi network.

### On your Android phone (Termux)

Install Termux from F-Droid, open it, and run:

```bash
pkg update && pkg install android-tools
termux-setup-storage    # lets the dump land in your phone's Download folder
curl -LO https://raw.githubusercontent.com/daveroz/Cam-deco/ccr-beaea807-gbu79w/scripts/chromecast-dump.sh
bash chromecast-dump.sh 192.168.x.x    # your Chromecast's IP
```

Accept the **Allow debugging?** prompt on the TV the first time, then run the last
command again. When it finishes, the files are in `Download/chromecast-dump`.

If Wireless debugging shows a pairing code, run `adb pair IP:PORT` with the code
first.
