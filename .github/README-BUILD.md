# Building the APK with GitHub Actions

## Automatic
- Push to `main` / `master`, or open a PR → workflow **Build APK** runs.
- Or: **Actions → Build APK → Run workflow**.

## Download the APK
1. Open the successful run under **Actions**.
2. Scroll to **Artifacts**.
3. Download **arcar-debug-apk** → unzip → `app-debug.apk`.

## Install on a phone
```bash
adb install -r app-debug.apk
```

## Requirements in the repo
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (committed)
- NDK version must match `app/build.gradle` (`26.1.10909125`)
