# Cam-deco

Tools for a Chromecast with Google TV (HD, codename `boreal`, Android TV OS 14).

- **[Deco Launcher](launcher/README.md):** a lightweight, ad-free home screen with an Apple TV-style layout and Worldly's look. Built by GitHub Actions; the latest APK is always at [`releases/download/launcher-latest/DecoLauncher.apk`](https://github.com/daveroz/Cam-deco/releases/download/launcher-latest/DecoLauncher.apk).
- **[`scripts/chromecast-dump.sh`](#copy-the-firmware-files-off-the-chromecast):** copies the readable firmware files off the device.

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
