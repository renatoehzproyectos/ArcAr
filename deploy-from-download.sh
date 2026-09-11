#!/usr/bin/env bash
# Unzip ArcAr-Android.zip desde Download → ~/ArcAr → push + bajar APK
set -euo pipefail

DOWNLOAD="${DOWNLOAD:-/storage/emulated/0/Download}"
ZIP="$DOWNLOAD/ArcAr-Android.zip"
STAGE="$DOWNLOAD/ArcAr-Android"
REPO="${REPO:-$HOME/ArcAr}"

[ -f "$ZIP" ] || { echo "ERROR: falta $ZIP"; exit 1; }

echo "==> Unzip $ZIP"
rm -rf "$STAGE"
unzip -q -o "$ZIP" -d "$STAGE"

if [ -d "$STAGE/ArcArAndroid" ]; then SRC="$STAGE/ArcArAndroid"
elif [ -d "$STAGE/app" ]; then SRC="$STAGE"
else echo "ERROR: estructura del zip inesperada"; find "$STAGE" -maxdepth 2; exit 1
fi

echo "==> Sync → $REPO (se conserva .git)"
[ -d "$REPO/.git" ] || { echo "ERROR: $REPO no es repo git"; exit 1; }
cd "$REPO"
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC"/. "$REPO"/

bash "$REPO/push-and-get-apk.sh" "$REPO"
