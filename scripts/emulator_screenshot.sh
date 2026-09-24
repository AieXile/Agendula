#!/usr/bin/env bash
# Capture the emulator screen and frame it in the AVD's device skin, like
# Android Studio's "Take screenshot" with the device frame on.
#
#   usage: scripts/emulator_screenshot.sh <name> [serial]
#   writes design/store/raw/<name>.png and design/store/framed/<name>.png
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${1:?need a name, e.g. 01-today}"
SERIAL="${2:-emulator-5554}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"

AVD=$(adb -s "$SERIAL" emu avd name | head -1 | tr -d '\r')
SKIN=$(grep -oP '^skin.path=\K.*' "$HOME/.android/avd/$AVD.avd/config.ini" || true)
[ -n "$SKIN" ] || SKIN="$SDK/skins/$(grep -oP '^skin.name=\K.*' "$HOME/.android/avd/$AVD.avd/config.ini")"
[ -f "$SKIN/layout" ] || { echo "No skin layout for $AVD at $SKIN" >&2; exit 1; }

# Screen offset inside the frame: the `device` part of the portrait layout.
read -r X Y < <(awk '/part2/{p=1} p&&/ x /{x=$2} p&&/ y /{print x, $2; exit}' "$SKIN/layout")

mkdir -p design/store/raw design/store/framed
RAW="design/store/raw/$NAME.png"
OUT="design/store/framed/$NAME.png"
adb -s "$SERIAL" exec-out screencap -p > "$RAW"

BACK=$(ls "$SKIN"/back.* | head -1)
MASK=$(ls "$SKIN"/mask.* 2>/dev/null | head -1 || true)
magick "$BACK" -alpha set -background none \
  \( -size "$(magick identify -format '%wx%h' "$BACK")" xc:none \
     "$RAW" -geometry "+$X+$Y" -composite \
     ${MASK:+"$MASK" -geometry "+$X+$Y" -composite} \) \
  +swap -composite "$OUT"

echo "$OUT ($(magick identify -format '%wx%h' "$OUT"), frame $(basename "$SKIN"))"
