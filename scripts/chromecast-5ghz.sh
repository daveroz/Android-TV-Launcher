#!/usr/bin/env bash
# chromecast-5ghz.sh - turn off the Google TV home screen (Deco Launcher takes over), then check
# that the Chromecast can see your 5 GHz WiFi, join it, and reach the internet.
# Run from Termux (or any computer with adb) on the same network as the TV.
#
# Usage:
#   bash chromecast-5ghz.sh <tv-ip> "<5 GHz network name>" [wpa2|wpa3|open]
#   bash chromecast-5ghz.sh <tv-ip> --undo        turn the Google TV home screen back on
#
# The WiFi password is asked for (and not shown) only if the TV has to join the network.

set -u

GOOGLE_TV=com.google.android.apps.tv.launcherx
GOOGLE_SETUP=com.google.android.tungsten.setupwraith
DECO=com.camdeco.launcher

if [ $# -lt 2 ]; then
  sed -n '6,9p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi

TV=$1
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

say() { printf '\n== %s\n' "$*"; }
ok() { printf '  OK  %s\n' "$*"; }
bad() { printf '  !!  %s\n' "$*"; }
# Run a command on the TV (stdin closed so adb never eats the script's input).
tv() { adb -s "$TV" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }
connect_tv() {
  adb connect "$TV" >/dev/null 2>&1 </dev/null
  [ "$(adb -s "$TV" get-state 2>/dev/null </dev/null)" = device ]
}
# "Frequency: 5180" from `cmd wifi status`, or empty.
frequency_of() { printf '%s\n' "$1" | grep -o 'Frequency: [0-9]*' | head -1 | grep -o '[0-9]*'; }
channel_of() { echo $(( ($1 - 5000) / 5 )); }

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. In Termux run: pkg install android-tools"
  exit 1
fi

say "Connecting to the TV at $TV"
if ! connect_tv; then
  bad "Can't reach the TV. Check the IP address, that USB debugging is on, and accept the prompt on the TV."
  exit 1
fi
ok "connected"

if [ "$2" = "--undo" ]; then
  say "Turning the Google TV home screen back on"
  tv pm enable "$GOOGLE_TV" >/dev/null
  tv pm enable "$GOOGLE_SETUP" >/dev/null
  if tv pm list packages -d | grep -q "$GOOGLE_TV"; then bad "still off"; else ok "Google TV home screen is on again"; fi
  exit 0
fi

SSID=$2
SECURITY=${3:-wpa2}

# ---- 1. Google TV home screen off ----
say "1. Turning off the Google TV home screen"
if [ -z "$(tv pm path "$DECO")" ]; then
  bad "Deco Launcher isn't installed, so Google TV stays on (otherwise there'd be no home screen)."
  exit 1
fi
tv pm disable-user --user 0 "$GOOGLE_TV" >/dev/null
tv pm disable-user --user 0 "$GOOGLE_SETUP" >/dev/null
disabled=$(tv pm list packages -d)
if printf '%s\n' "$disabled" | grep -q "$GOOGLE_TV"; then ok "Google TV home screen is off; Deco Launcher is Home"; else bad "Google TV home screen is still on"; fi
if printf '%s\n' "$disabled" | grep -q "$GOOGLE_SETUP"; then ok "Google TV setup app is off"; else bad "Google TV setup app is still on"; fi

# ---- 2. WiFi now ----
say "2. WiFi right now"
status=$(tv cmd wifi status)
freq=$(frequency_of "$status")
current=$(printf '%s\n' "$status" | grep -o 'connected to "[^"]*"' | head -1 | sed 's/connected to //')
if [ -n "$current" ]; then
  band="2.4 GHz"; [ "${freq:-0}" -ge 4900 ] && band="5 GHz, channel $(channel_of "$freq")"
  echo "  Connected to $current (${freq:-?} MHz, $band)"
else
  echo "  Not connected to WiFi"
fi
country=$(tv cmd wifi get-country-code)
echo "  ${country:-Country code: unknown}"
case "$country" in
  *" 00"*|*"=00"*|*"= 00"*) bad "The TV is in 'world' mode, which allows fewer 5 GHz channels. Router channels 36-48 still work." ;;
esac

# ---- 3. Can the TV see the 5 GHz network? ----
say "3. Looking for \"$SSID\" on 5 GHz"
tv cmd wifi start-scan >/dev/null
sleep 6
scan=$(tv cmd wifi list-scan-results)
# Columns: BSSID, Frequency, RSSI, Age, SSID, Flags. The name is padded with spaces, so two
# spaces after it means an exact match ("Home 5G" doesn't match "Home 5G Guest").
matches=$(printf '%s\n' "$scan" | grep -F -- "  $SSID  ")
five=$(printf '%s\n' "$matches" | awk '$2 >= 4900 && $2 < 6000 { print $2, $3 }')
if [ -z "$five" ]; then
  bad "The TV can't see \"$SSID\" on 5 GHz."
  if [ -n "$matches" ]; then
    echo "      It only sees it on: $(printf '%s\n' "$matches" | awk '{ print $2 " MHz" }' | sort -u | tr '\n' ' ')"
  fi
  echo "      Most likely the router's 5 GHz channel: set it to 36, 40, 44 or 48 (not 52-144),"
  echo "      give 5 GHz its own network name (turn off Smart Connect / band steering), then run this again."
  exit 1
fi
printf '%s\n' "$five" | while read -r f rssi; do
  ok "seen on channel $(channel_of "$f") ($f MHz), signal $rssi dBm"
done

# ---- 4. Join it ----
say "4. Joining \"$SSID\""
if [ "$current" = "\"$SSID\"" ] && [ "${freq:-0}" -ge 4900 ]; then
  ok "already connected on 5 GHz"
else
  q() { printf '%q' "$1"; } # quote for the TV's shell
  if [ "$SECURITY" = "open" ]; then
    out=$(adb -s "$TV" shell "cmd wifi connect-network $(q "$SSID") open" 2>&1 </dev/null)
  else
    printf '  WiFi password for "%s" (not shown): ' "$SSID"
    read -rs PASSWORD
    echo
    out=$(adb -s "$TV" shell "cmd wifi connect-network $(q "$SSID") $SECURITY $(q "$PASSWORD")" 2>&1 </dev/null)
    unset PASSWORD
  fi
  [ -n "$out" ] && echo "  $(printf '%s' "$out" | tr -d '\r' | head -2)"
  echo "  The TV is switching networks, so the link to it drops for a moment..."
  sleep 10
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    connect_tv && break
    sleep 5
  done
  if ! connect_tv; then
    bad "Lost the link to the TV: its address probably changed on the 5 GHz network."
    echo "      On the TV: Settings -> Network & Internet -> $SSID shows the new IP address. Then run:"
    echo "      adb connect NEW-IP:5555 && adb shell cmd wifi status | grep -o 'Frequency: [0-9]*'"
    exit 1
  fi
fi

# ---- 5. Check ----
say "5. Checking the connection"
status=$(tv cmd wifi status)
freq=$(frequency_of "$status")
ip=$(printf '%s\n' "$status" | grep -o 'IP: /[0-9.]*' | head -1 | cut -d/ -f2)
if printf '%s\n' "$status" | grep -qF "connected to \"$SSID\"" && [ "${freq:-0}" -ge 4900 ]; then
  ok "connected to \"$SSID\" on 5 GHz: channel $(channel_of "$freq") ($freq MHz), TV address ${ip:-?}"
else
  bad "not on \"$SSID\" at 5 GHz (frequency ${freq:-none}). Check the password and the router, and try again."
  exit 1
fi
if [ "$(adb -s "$TV" shell 'ping -c 3 -W 3 8.8.8.8 >/dev/null 2>&1 && echo yes' 2>/dev/null </dev/null | tr -d '\r')" = yes ]; then
  ok "internet works"
else
  bad "connected, but the internet isn't reachable (router or DNS problem)"
fi

echo
echo "Done. To turn Google TV back on: bash $0 ${TV%:5555} --undo"
