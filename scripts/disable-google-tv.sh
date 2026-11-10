#!/usr/bin/env bash
# disable-google-tv.sh - safely turn off the Google TV home screen so Deco Launcher is Home.
# Run from Termux (or any computer with adb) on the same network as the TV.
#
# Usage:
#   bash disable-google-tv.sh <tv-ip>           turn Google TV's home screen off (asks first)
#   bash disable-google-tv.sh <tv-ip> --undo    turn it back on
#
# What it changes: Deco Launcher becomes the Home app, then only the Google TV home screen app
# (com.google.android.apps.tv.launcherx) is turned off with "pm disable-user". Nothing is
# uninstalled or deleted, and --undo restores it. It checks Home, WiFi, internet, Settings,
# casting, Play Store, Google Play services, voice search and the keyboard before and after,
# and puts Google TV back by itself if Home doesn't end up on Deco Launcher.

set -u

GOOGLE_TV=com.google.android.apps.tv.launcherx
GOOGLE_SETUP=com.google.android.tungsten.setupwraith
DECO=com.camdeco.launcher
DECO_HOME=com.camdeco.launcher/com.camdeco.launcher.HomeActivity
HOME_ROLE=android.app.role.HOME
# Apps that must keep working: package|what it is.
KEEP="com.android.tv.settings|Settings
com.google.android.gms|Google Play services
com.android.vending|Play Store
com.google.android.apps.mediashell|Casting (Chromecast built-in)
com.google.android.katniss|Voice search / Assistant
com.google.android.inputmethod.latin|Keyboard (Gboard)
com.google.android.leanback.ime|Keyboard (Leanback)"

if [ $# -lt 1 ]; then
  sed -n '5,7p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi
TV=$1
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
# Accept --undo, -undo or undo (and the same for --yes); refuse anything else.
case "${2:-}" in
  "") MODE="" ;;
  --undo | -undo | undo) MODE="--undo" ;;
  --yes | -yes | yes | -y) MODE="--yes" ;;
  *) echo "Unknown option: $2 (use --undo to turn Google TV back on)"; exit 1 ;;
esac

say() { printf '\n== %s\n' "$*"; }
ok() { printf '  OK  %s\n' "$*"; }
bad() { printf '  !!  %s\n' "$*"; }
note() { printf '  --  %s\n' "$*"; }
# Run a command on the TV (stdin closed so adb never eats the script's input).
tv() { adb -s "$TV" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }
connect_tv() {
  adb connect "$TV" >/dev/null 2>&1 </dev/null
  [ "$(adb -s "$TV" get-state 2>/dev/null </dev/null)" = device ]
}
installed() { [ -n "$(tv pm path "$1")" ]; }
disabled_list() { tv pm list packages -d | sed 's/^package://' | sort; }
is_disabled() { disabled_list | grep -qx "$1"; }
# The app the Home button goes to, e.g. com.camdeco.launcher/.HomeActivity.
home_target() {
  tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
}
# Press Home and report which app is on screen.
press_home() {
  tv input keyevent KEYCODE_HOME >/dev/null
  sleep 5 # the Chromecast HD can take a few seconds to start an app
  tv dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2
}
wifi_now() {
  local status ssid freq
  status=$(tv cmd wifi status)
  ssid=$(printf '%s\n' "$status" | grep -o 'connected to "[^"]*"' | head -1 | sed 's/connected to //')
  freq=$(printf '%s\n' "$status" | grep -o 'Frequency: [0-9]*' | head -1 | grep -o '[0-9]*')
  if [ -z "$ssid" ]; then echo "not connected"; return; fi
  if [ "${freq:-0}" -ge 4900 ]; then echo "$ssid on 5 GHz"; else echo "$ssid on 2.4 GHz"; fi
}
internet() { [ "$(tv 'ping -c 2 -W 3 8.8.8.8 >/dev/null 2>&1 && echo yes')" = yes ]; }
confirm() {
  [ "${YES:-}" = 1 ] && return 0
  printf '%s [y/N] ' "$1"
  read -r answer
  case "$answer" in y | Y | yes | YES) return 0 ;; *) return 1 ;; esac
}

check_apps() {
  local disabled line pkg name
  disabled=$(disabled_list)
  while IFS='|' read -r pkg name; do
    if ! installed "$pkg"; then continue; fi
    if printf '%s\n' "$disabled" | grep -qx "$pkg"; then bad "$name is turned off"; else ok "$name"; fi
  done <<< "$KEEP"
  if [ "$(tv 'am start -W -a android.settings.SETTINGS' | grep -c 'Status: ok')" -ge 1 ]; then
    ok "Settings opens"
  else
    bad "Settings didn't open"
  fi
  tv input keyevent KEYCODE_HOME >/dev/null
}

turn_google_tv_back_on() {
  tv pm enable "$GOOGLE_TV" >/dev/null
  if [ "${1:-}" = setup-too ] && is_disabled "$GOOGLE_SETUP"; then tv pm enable "$GOOGLE_SETUP" >/dev/null; fi
  tv cmd role add-role-holder --user 0 "$HOME_ROLE" "$GOOGLE_TV" >/dev/null
}

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

# ---------------------------------------------------------------- undo
if [ "$MODE" = "--undo" ]; then
  say "Turning the Google TV home screen back on"
  turn_google_tv_back_on setup-too
  if is_disabled "$GOOGLE_TV"; then bad "Google TV home screen is still off"; exit 1; fi
  ok "Google TV home screen is on"
  if press_home | grep -q "$GOOGLE_TV"; then ok "Home opens Google TV"; else note "Home: $(home_target) (choose Google TV if the TV asks)"; fi
  exit 0
fi
[ "$MODE" = "--yes" ] && YES=1

# ---------------------------------------------------------------- checks before
say "1. Checking before changing anything"
if ! installed "$DECO" || is_disabled "$DECO"; then
  bad "Deco Launcher isn't installed (or is turned off). Install it first; nothing was changed."
  exit 1
fi
ok "Deco Launcher is installed"
if ! installed "$GOOGLE_TV"; then
  bad "This TV doesn't have the Google TV home screen app ($GOOGLE_TV). Nothing to do."
  exit 1
fi
before_disabled=$(disabled_list)
before_wifi=$(wifi_now)
record="google-tv-before-$(date +%Y%m%d-%H%M%S).txt"
{
  echo "Apps already turned off before disable-google-tv.sh ran:"
  printf '%s\n' "$before_disabled"
  echo "WiFi: $before_wifi"
} > "$record"
ok "Saved what was already turned off to $record"
echo "  WiFi: $before_wifi"
if internet; then ok "internet works"; before_internet=1; else note "no internet right now"; before_internet=0; fi
echo "  Home button goes to: $(home_target)"

if is_disabled "$GOOGLE_TV"; then
  note "The Google TV home screen is already off; checking that Home works."
else
  echo
  echo "  This turns off ONLY the Google TV home screen ($GOOGLE_TV)."
  echo "  Nothing is uninstalled or deleted. Undo any time with: bash $0 ${TV%:5555} --undo"
  if ! confirm "  Continue?"; then echo "  Nothing changed."; exit 0; fi
fi

# ---------------------------------------------------------------- change
say "2. Making Deco Launcher the Home app"
tv cmd package set-home-activity --user 0 "$DECO_HOME" >/dev/null
case "$(home_target)" in
  *"$DECO"*) ok "Home is set to Deco Launcher" ;;
  *) tv cmd role add-role-holder --user 0 "$HOME_ROLE" "$DECO" >/dev/null
     case "$(home_target)" in *"$DECO"*) ok "Home is set to Deco Launcher" ;; *) note "Home is still $(home_target); continuing" ;; esac ;;
esac

say "3. Turning off the Google TV home screen"
tv pm disable-user --user 0 "$GOOGLE_TV" >/dev/null
if is_disabled "$GOOGLE_TV"; then ok "Google TV home screen is off"; else bad "Couldn't turn it off; nothing else was changed."; exit 1; fi

say "4. Checking the Home button"
if press_home | grep -q "$DECO"; then
  ok "Home opens Deco Launcher"
else
  bad "Home doesn't open Deco Launcher (it goes to: $(home_target))."
  setup_off=0
  if installed "$GOOGLE_SETUP" && ! is_disabled "$GOOGLE_SETUP" && home_target | grep -q "$GOOGLE_SETUP"; then
    echo "  Google TV's setup app is claiming the Home button. Turning it off as well usually fixes this"
    echo "  (also undone by --undo)."
    if confirm "  Turn off the Google TV setup app too?"; then
      tv pm disable-user --user 0 "$GOOGLE_SETUP" >/dev/null
      setup_off=1
    fi
  fi
  if [ "$setup_off" = 1 ] && press_home | grep -q "$DECO"; then
    ok "Home opens Deco Launcher"
  else
    bad "Putting Google TV back so the TV keeps a working Home screen."
    turn_google_tv_back_on setup-too
    press_home >/dev/null
    is_disabled "$GOOGLE_TV" && bad "Google TV is still off: run  bash $0 ${TV%:5555} --undo" || ok "Google TV is back; nothing was left changed."
    exit 1
  fi
fi

# ---------------------------------------------------------------- checks after
say "5. Checking that everything else still works"
after_wifi=$(wifi_now)
if [ "$after_wifi" = "$before_wifi" ]; then ok "WiFi unchanged: $after_wifi"; else bad "WiFi changed: was $before_wifi, now $after_wifi"; fi
if internet; then ok "internet works"; elif [ "$before_internet" = 1 ]; then bad "internet stopped working"; else note "no internet (there wasn't any before either)"; fi
check_apps
newly_off=$(comm -13 <(printf '%s\n' "$before_disabled") <(disabled_list) | grep -v '^$')
others=$(printf '%s\n' "$newly_off" | grep -vx -e "$GOOGLE_TV" -e "$GOOGLE_SETUP" | grep -v '^$')
if [ -z "$others" ]; then
  ok "nothing else was turned off (only: $(printf '%s' "$newly_off" | tr '\n' ' '))"
else
  bad "these were turned off too: $(printf '%s' "$others" | tr '\n' ' ')"
fi

echo
echo "Done. Deco Launcher is your Home screen."
echo "To turn Google TV back on:  bash $0 ${TV%:5555} --undo"
