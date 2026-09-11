#!/usr/bin/env bash
set -euo pipefail
DOWNLOAD="${DOWNLOAD:-/storage/emulated/0/Download}"
ZIP="$DOWNLOAD/ArcAr-Android.zip"
STAGE="$DOWNLOAD/ArcAr-Android"
REPO="${REPO:-$HOME/ArcAr}"
[ -f "$ZIP" ] || { echo "ERROR: falta $ZIP"; exit 1; }
rm -rf "$STAGE"
unzip -q -o "$ZIP" -d "$STAGE"
if [ -d "$STAGE/ArcArAndroid" ]; then SRC="$STAGE/ArcArAndroid"
elif [ -d "$STAGE/app" ]; then SRC="$STAGE"
else echo "ERROR: zip inesperado"; find "$STAGE" -maxdepth 2; exit 1; fi
[ -d "$REPO/.git" ] || { echo "ERROR: $REPO no es repo git"; exit 1; }
cd "$REPO"
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC"/. "$REPO"/
bash "$REPO/push-and-get-apk.sh" "$REPO"
