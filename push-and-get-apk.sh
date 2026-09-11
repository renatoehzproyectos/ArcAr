#!/usr/bin/env bash
# push-and-get-apk.sh — commit/push, track Build APK workflow, download APK
# Uso: bash push-and-get-apk.sh [ruta-del-repo]
set -euo pipefail

REPO_DIR="${1:-$HOME/ArcAr}"
if [ -d "$HOME/storage/downloads" ]; then
  OUT_DIR="${OUT_DIR:-$HOME/storage/downloads/ArcAr-APK}"
elif [ -d "/storage/emulated/0/Download" ]; then
  OUT_DIR="${OUT_DIR:-/storage/emulated/0/Download/ArcAr-APK}"
else
  OUT_DIR="${OUT_DIR:-$HOME/ArcAr-APK}"
fi

mkdir -p "$OUT_DIR"
cd "$REPO_DIR"

command -v gh >/dev/null || { echo "ERROR: instalá e autenticá GitHub CLI (gh)"; exit 1; }
command -v git >/dev/null || { echo "ERROR: falta git"; exit 1; }

REMOTE=$(git remote | head -1)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
SHA_BEFORE=$(git rev-parse HEAD)
echo "==> $REPO_DIR  remote=$REMOTE  branch=$BRANCH"

if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -m "Update ArcAr Android" || true
fi

SHA=$(git rev-parse HEAD)
echo "==> Push $REMOTE $BRANCH ($SHA)..."
git push "$REMOTE" "$BRANCH"

echo "==> Buscando run de workflow 'Build APK' para este commit..."
RUN_ID=""
for i in $(seq 1 20); do
  # Prefer run matching our SHA
  RUN_ID=$(gh run list --workflow="Build APK" --branch="$BRANCH" --limit 5 \
    --json databaseId,headSha,status \
    --jq ".[] | select(.headSha==\"$SHA\") | .databaseId" 2>/dev/null | head -1 || true)
  if [ -n "$RUN_ID" ]; then
    break
  fi
  echo "   [$i/20] run aún no visible..."
  sleep 3
done

if [ -z "$RUN_ID" ]; then
  echo "ERROR: no apareció el run. Últimos runs:"
  gh run list --workflow="Build APK" --limit 5 || true
  exit 1
fi

echo "==> Run #$RUN_ID — siguiendo progreso"
# gh run watch imprime estado en vivo; si falla usamos poll
if gh run watch "$RUN_ID" --exit-status 2>/dev/null; then
  :
else
  # Fallback poll con % aproximado
  while true; do
    VIEW=$(gh run view "$RUN_ID" --json status,conclusion,jobs 2>/dev/null || echo '{}')
    STATUS=$(echo "$VIEW" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    CONCLUSION=$(echo "$VIEW" | grep -o '"conclusion":"[^"]*"' | head -1 | cut -d'"' -f4)
    # steps: completed vs total
    COMP=$(echo "$VIEW" | grep -c '"status":"completed"' || true)
    TOTAL=$(echo "$VIEW" | grep -c '"name":' || true)
    if [ "${TOTAL:-0}" -gt 0 ]; then
      PCT=$((COMP * 100 / TOTAL))
      [ "$PCT" -gt 99 ] && [ "$STATUS" != "completed" ] && PCT=95
    else
      case "$STATUS" in queued) PCT=5;; in_progress) PCT=45;; completed) PCT=100;; *) PCT=15;; esac
    fi
    printf "\r   [%3d%%] %s %s    " "$PCT" "$STATUS" "${CONCLUSION:-}"
    [ "$STATUS" = "completed" ] && echo && break
    sleep 6
  done
  [ "${CONCLUSION:-}" = "success" ] || { echo "ERROR: build=$CONCLUSION"; gh run view "$RUN_ID" --log-failed 2>/dev/null | tail -60; exit 1; }
fi

echo "==> Descargando artifact → $OUT_DIR"
rm -rf "${OUT_DIR:?}/"*
cd "$OUT_DIR"
gh run download "$RUN_ID" -n arcar-debug-apk

APK=$(find . -name 'app-debug.apk' -type f | head -1)
if [ -z "$APK" ]; then
  for z in ./*.zip; do [ -f "$z" ] && unzip -qo "$z"; done
  APK=$(find . -name 'app-debug.apk' -type f | head -1)
fi
[ -n "$APK" ] || { echo "ERROR: sin app-debug.apk"; find . -type f; exit 1; }

cp -f "$APK" "$OUT_DIR/app-debug.apk"
echo ""
echo "============================================"
echo " APK listo: $OUT_DIR/app-debug.apk"
echo " Instalar:  adb install -r $OUT_DIR/app-debug.apk"
echo "============================================"
