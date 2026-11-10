#!/usr/bin/env bash
# chromecast-dump.sh - copy everything readable off a Chromecast with Google TV
# over ADB, without root. Made for Termux on Android; also works on any Linux
# machine with adb installed.
#
# Usage: bash chromecast-dump.sh <tv-ip[:port]> [output-dir]
#
# You get: device info, the system/vendor/product file trees as .tar archives,
# and the APKs of installed and updated apps.
# You do NOT get: boot, kernel, bootloader or other raw partition images.
# Reading those needs root, so this is a reference copy, not a flashable image.

set -u

if [ $# -lt 1 ]; then
  echo "Usage: bash $0 <tv-ip[:port]> [output-dir]"
  exit 1
fi

TARGET=$1
case "$TARGET" in *:*) ;; *) TARGET="$TARGET:5555" ;; esac

if [ -d "$HOME/storage/downloads" ]; then
  OUT=${2:-"$HOME/storage/downloads/chromecast-dump"}   # Termux: phone's Download folder
else
  OUT=${2:-"$PWD/chromecast-dump"}
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. In Termux run: pkg install android-tools"
  exit 1
fi

adb connect "$TARGET" </dev/null
state=$(adb -s "$TARGET" get-state 2>&1 </dev/null)
if [ "$state" != "device" ]; then
  echo "Chromecast not ready: $state"
  echo "Accept the 'Allow debugging?' prompt on the TV, then run this again."
  exit 1
fi

# Run a command on the TV. stdin is closed so adb can't eat a loop's input.
tv() { adb -s "$TARGET" shell "$1" </dev/null 2>/dev/null | tr -d '\r'; }

mkdir -p "$OUT/info" "$OUT/apks"
echo "Saving to $OUT"

echo "[1/3] Device info"
tv getprop                    > "$OUT/info/getprop.txt"
tv 'cat /proc/version'        > "$OUT/info/kernel.txt"
tv 'cat /proc/cpuinfo'        > "$OUT/info/cpuinfo.txt"
tv 'cat /proc/partitions'     > "$OUT/info/partitions.txt"
tv 'ls -l /dev/block/by-name' > "$OUT/info/partition-names.txt"
tv mount                      > "$OUT/info/mounts.txt"
tv 'df -h'                    > "$OUT/info/disk-usage.txt"
tv 'pm list packages -f'      > "$OUT/info/packages.txt"
echo "  $(tv 'getprop ro.build.fingerprint')"

echo "[2/3] System file trees (the slow part, roughly 1-3 GB)"
for part in system system_ext product vendor odm; do
  # Skip partitions that don't exist or are only a link into /system.
  [ "$(tv "[ -d /$part ] && [ ! -L /$part ] && echo y")" = y ] || continue
  echo "  /$part"
  adb -s "$TARGET" exec-out "cd / && tar -cf - $part 2>/dev/null" </dev/null > "$OUT/$part.tar"
  if [ ! -s "$OUT/$part.tar" ]; then
    echo "    nothing readable in /$part"
    rm -f "$OUT/$part.tar"
  fi
done

echo "[3/3] APKs of installed and updated apps"
# Built-in apps are already inside the .tar files above. This copies the ones
# stored under /data: apps you installed and updated versions of Google apps.
tv 'pm list packages -f' | sed -n 's|^package:\(/data/.*\)=\([^=]*\)$|\2|p' |
while read -r pkg; do
  mkdir -p "$OUT/apks/$pkg"
  tv "pm path $pkg" | sed 's/^package://' | while read -r apk; do
    adb -s "$TARGET" pull "$apk" "$OUT/apks/$pkg/" </dev/null >/dev/null 2>&1 ||
      echo "  could not copy $apk"
  done
done

if command -v sha256sum >/dev/null 2>&1; then
  echo "Writing checksums"
  (cd "$OUT" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
fi

echo "Done: $(du -sh "$OUT" | cut -f1) in $OUT"
