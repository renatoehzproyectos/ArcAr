# Building the APK with GitHub Actions

## Automatic
- Push to `main` / `master` → workflow **Build APK** runs.
- Or: **Actions → Build APK → Run workflow**.

## Download the APK
1. Open the successful run under **Actions**.
2. Artifacts → **arcar-debug-apk** → unzip → `app-debug.apk`.

Or with `gh`:
```bash
gh run download -n arcar-debug-apk -R <owner>/<repo>
```

## Install
```bash
adb install -r app-debug.apk
```
