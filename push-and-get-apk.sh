#!/usr/bin/env bash
# push → wait Build APK → download artifact → leave APK in Download
set -euo pipefail

REPO_DIR="${1:-$HOME/ArcAr}"
if [ -d "/storage/emulated/0/Download" ]; then
  DOWNLOAD="/storage/emulated/0/Download"
elif [ -d "$HOME/storage/downloads" ]; then
  DOWNLOAD="$HOME/storage/downloads"
else
  DOWNLOAD="${HOME}/Download"
  mkdir -p "$DOWNLOAD"
fi
OUT_DIR="${OUT_DIR:-$DOWNLOAD/ArcAr-APK}"
APK_EASY="$DOWNLOAD/ArcAr.apk"

mkdir -p "$OUT_DIR"
cd "$REPO_DIR"

command -v gh >/dev/null || { echo "ERROR: instalá gh y hacé: gh auth login"; exit 1; }
command -v git >/dev/null || { echo "ERROR: falta git"; exit 1; }
command -v unzip >/dev/null || { echo "ERROR: falta unzip"; exit 1; }

REMOTE=$(git remote | head -1)
BRANCH=$(git rev-parse --abbrev-ref HEAD)
OWNER_REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)
if [ -z "$OWNER_REPO" ]; then
  # fallback from remote url
  URL=$(git remote get-url "$REMOTE")
  OWNER_REPO=$(echo "$URL" | sed -E 's#.*[:/]([^/]+/[^/]+)(\.git)?$#\1#' | sed 's/\.git$//')
fi
echo "==> repo=$OWNER_REPO  remote=$REMOTE  branch=$BRANCH  dir=$REPO_DIR"

if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -m "Update ArcAr Android" || true
fi
SHA=$(git rev-parse HEAD)
echo "==> Push $SHA ..."
git push "$REMOTE" "$BRANCH"

echo "==> Esperando run 'Build APK' para $SHA"
RUN_ID=""
for i in $(seq 1 30); do
  RUN_ID=$(gh run list --workflow="Build APK" --branch="$BRANCH" --limit 8 \
    --json databaseId,headSha,status,createdAt \
    --jq ".[] | select(.headSha==\"$SHA\") | .databaseId" 2>/dev/null | head -1 || true)
  if [ -n "$RUN_ID" ]; then
    echo "   encontrado run=$RUN_ID (intento $i)"
    break
  fi
  printf "   [%2d/30] aún no aparece...\n" "$i"
  sleep 2
done
if [ -z "$RUN_ID" ]; then
  echo "ERROR: no hay run para este commit"
  gh run list --workflow="Build APK" --limit 5 || true
  exit 1
fi

echo "==> Esperando a que termine el build (run $RUN_ID)"
# Prefer gh run watch; poll fallback with progress
if ! gh run watch "$RUN_ID" --exit-status 2>/dev/null; then
  while true; do
    VIEW=$(gh run view "$RUN_ID" --json status,conclusion 2>/dev/null || echo '{}')
    STATUS=$(echo "$VIEW" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)
    CONCLUSION=$(echo "$VIEW" | sed -n 's/.*"conclusion":"\([^"]*\)".*/\1/p' | head -1)
    case "$STATUS" in
      queued) PCT=8 ;;
      waiting) PCT=12 ;;
      in_progress) PCT=55 ;;
      completed) PCT=100 ;;
      *) PCT=20 ;;
    esac
    printf "\r   [%3d%%] status=%s conclusion=%s   " "$PCT" "${STATUS:-?}" "${CONCLUSION:-…}"
    if [ "$STATUS" = "completed" ]; then
      echo
      break
    fi
    sleep 5
  done
  if [ "${CONCLUSION:-}" != "success" ]; then
    echo "ERROR: build falló ($CONCLUSION)"
    gh run view "$RUN_ID" --log-failed 2>/dev/null | tail -80 || true
    exit 1
  fi
fi

echo "==> Descargando artifact arcar-debug-apk"
rm -rf "${OUT_DIR:?}"
mkdir -p "$OUT_DIR"
TMP="$OUT_DIR/.dl"
mkdir -p "$TMP"
cd "$TMP"

download_ok=0

# Method 1: gh run download (extracts automatically in recent gh)
if gh run download "$RUN_ID" -n arcar-debug-apk -D "$TMP" 2>"$OUT_DIR/gh-download.err"; then
  download_ok=1
else
  echo "   gh run download falló, probando API zip..."
  # Method 2: REST API → artifact zip
  ART_ID=$(gh api "repos/$OWNER_REPO/actions/runs/$RUN_ID/artifacts" \
    --jq '.artifacts[] | select(.name=="arcar-debug-apk") | .id' 2>/dev/null | head -1 || true)
  if [ -n "$ART_ID" ]; then
    echo "   artifact id=$ART_ID"
    if gh api "repos/$OWNER_REPO/actions/artifacts/$ART_ID/zip" > "$TMP/artifact.zip" 2>/dev/null; then
      if [ -s "$TMP/artifact.zip" ]; then
        unzip -qo "$TMP/artifact.zip" -d "$TMP"
        download_ok=1
      fi
    fi
  fi
fi

if [ "$download_ok" != 1 ]; then
  echo "ERROR: no se pudo descargar el artifact"
  cat "$OUT_DIR/gh-download.err" 2>/dev/null || true
  ls -la "$TMP" || true
  exit 1
fi

# Any nested zips
find "$TMP" -name '*.zip' -type f | while read -r z; do
  unzip -qo "$z" -d "$TMP" || true
done

APK=$(find "$TMP" -name 'app-debug.apk' -type f | head -1)
if [ -z "$APK" ]; then
  APK=$(find "$TMP" -name '*.apk' -type f | head -1)
fi
if [ -z "$APK" ]; then
  echo "ERROR: no hay .apk dentro del artifact. Contenido:"
  find "$TMP" -type f | head -40
  exit 1
fi

cp -f "$APK" "$OUT_DIR/app-debug.apk"
cp -f "$APK" "$APK_EASY"
# cleanup temp
rm -rf "$TMP"

SIZE=$(ls -lh "$APK_EASY" | awk '{print $5}')
echo ""
echo "============================================"
echo " APK listo ($SIZE):"
echo "   $APK_EASY"
echo "   $OUT_DIR/app-debug.apk"
echo " Abrí el archivo desde Descargas o:"
echo "   adb install -r $APK_EASY"
echo "============================================"
