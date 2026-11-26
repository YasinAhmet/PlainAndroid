# PlainAndroid

Small, offline-first Android apps with no accounts, ads or tracking, plus an installer to set them up.

## Install

1. On the phone, download **[PlainSetup.apk](https://github.com/YasinAhmet/PlainAndroid/releases/latest/download/PlainSetup.apk)** (the latest release) and open it. Allow your browser or file manager to install apps when Android asks.
2. Open **Plain Setup**, tick the apps you want and tap **Install**. Android asks you to confirm each app.
3. If Play Protect warns about an unknown app, tap *More details → Install anyway*. It warns because these apps don't come from the Play Store.

To update: every app has **Check for updates** in its settings or menu. Plain Setup downloads the new version from these releases and checks that it's signed with the same key before installing it. Plain Setup itself also shows available updates when you open it.

## Apps

| App | Android | What it does |
|---|---|---|
| Plain Music | 11+ | Offline music player for the songs on your phone. No internet permission. |
| Plain Gallery | 10+ | Photos and videos page by page, newest first. |
| Plain Camera | 10+ | Simple camera with an advanced mode. |
| Plain Trekking | 8+ | Hiking: plan routes, offline maps (downloaded on Wi-Fi), GPS trail recording. |
| Plain Istanbul Map | 8+ | Offline Istanbul bus/metro/ferry line map with stops and timetables. |
| Plain Youtube | 11+ | Search, watch and download YouTube videos and music (unofficial client). |
| Plain Home | 10+ | Minimal home screen with app sections. |
| Plain Battery | 10+ | Battery log; per-app drain needs a one-time adb grant. |
| Plain Uninstaller | 8+ | Bulk-remove apps, including preinstalled ones via [Shizuku](https://shizuku.rikka.app/). Use at your own risk; removed system apps can be restored. |
| Plain Setup | 8+ | Installs and updates all of the above. |

Apps that work offline (Music, Camera, Gallery, Home, Battery, Uninstaller) have no internet permission at all. Their *Check for updates* button hands the job to Plain Setup.

## Repository layout

- `apps.json`: the live catalog Plain Setup reads (latest version, download URL, SHA-256 and minimum Android version of each app).
- `catalog.json`: how each app is built (project folder, Gradle tasks, APK path, description).
- `publish.py`: bumps the version, builds, creates the GitHub release, rebuilds Plain Setup with every APK bundled, syncs the source and pushes.
- `src/<App>/`: source code of each app (Kotlin, plain Android framework, Gradle).
- `shared/PlainUpdate.kt`: the *Check for updates* helper every app carries a copy of.

## Publishing (maintainer)

The app projects live next to this repo (`A:\Projects\<project>`).

```powershell
python publish.py music maps        # release these apps, then a new Plain Setup
python publish.py all --bump        # new version of everything
python publish.py --sync-only       # only copy source into src/ and push
```

Release builds are signed with the key in `A:\Projects\PlainSigning`. That folder is never committed, and it must be backed up: without it, installed apps can't be updated. To build from this source without that key, the build falls back to your local debug key. Those APKs install fine on their own, but they can't update apps installed from these releases.
