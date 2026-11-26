# PlainAndroid

Small, offline-first Android apps with no accounts, ads or tracking, plus an installer to set them up.

## Install

1. On the phone, download **[PlainSetup.apk](https://github.com/YasinAhmet/PlainAndroid/releases/latest/download/PlainSetup.apk)** (the latest release; it installs **Plain Manager**, formerly Plain Setup) and open it. Allow your browser or file manager to install apps when Android asks.
2. Open **Plain Manager**, tick the apps you want and tap **Install**. Android asks you to confirm each app.
3. If Play Protect warns about an unknown app, tap *More details → Install anyway*. It warns because these apps don't come from the Play Store.

To update: every app has **Check for updates** in its settings or menu. Plain Manager downloads the new version from these releases and checks that it's signed with the same key before installing it. Plain Manager also shows available updates when you open it.

## Apps

Four apps with tabs (a dropdown at the top switches between them), plus Plain Home:

| App | Android | Tabs |
|---|---|---|
| Plain Manager | 8+ | **Setup**: installs and updates the Plain apps. **Battery**: battery log; per-app drain needs a one-time adb grant (Android 10+). **Uninstaller**: bulk-remove apps, including preinstalled ones via [Shizuku](https://shizuku.rikka.app/). Use at your own risk; removed system apps can be restored. |
| Plain Maps | 8+ | **Istanbul** and **Ankara**: offline bus/metro/ferry line maps with stops and timetables (each city syncs once over Wi-Fi). **Trekking**: plan hikes, offline maps (downloaded on Wi-Fi), GPS trail recording. |
| Plain Camera | 10+ | **Camera**: simple camera with an advanced mode. **Gallery**: photos and videos page by page, newest first. No internet permission. |
| Plain Media | 11+ | **Music**: offline player for the songs on your phone (it never goes online). **Youtube**: search, watch and download (unofficial client). **Spotify**: coming later. |
| Plain Home | 10+ | Minimal home screen with app sections. |

### Upgrading from the single apps

Until November 2026 these were ten separate apps. Each merged app keeps the package of one of them, so that one simply updates: Plain Setup → **Plain Manager**, Plain Istanbul Map → **Plain Maps**, Plain Camera → **Plain Camera**, Plain Youtube → **Plain Media**, with all their data.

The others (Plain Battery, Plain Uninstaller, Plain Trekking, Plain Gallery, Plain Music) get one last update that lets the new app copy their data. Open the new app's tab: it asks *Copy / Start fresh / Not now*. Copying never changes or deletes anything in the old app; afterwards it offers to remove the old app. Plain Manager lists these old apps only while they are installed.

Plain Battery's per-app drain needs its adb grant again for Plain Manager (`app.plain.setup`); the Battery tab shows the commands.

## Repository layout

- `apps.json`: the live catalog Plain Manager reads (latest version, download URL, SHA-256 and minimum Android version of each app).
- `catalog.json`: how each app is built (project folder, Gradle tasks, APK path, description; `mergedInto` for former apps).
- `publish.py`: bumps the version, builds, creates the GitHub release, rebuilds Plain Manager with every APK bundled, syncs the source and pushes.
- `src/<App>/`: source code of each app (Kotlin, plain Android framework, Gradle).
- `src/PlainShared/`: the module the merged apps share (tab header, data import from former apps, languages, updates).
- `shared/PlainUpdate.kt`, `shared/PlainLang.kt`: copies of the shared update and language helpers.

## Publishing (maintainer)

The app projects live next to this repo (`A:\Projects\<project>`).

```powershell
python publish.py maps media        # release these apps, then a new Plain Manager
python publish.py all --bump        # new version of everything
python publish.py --sync-only       # only copy source into src/ and push
```

Release builds are signed with the key in `A:\Projects\PlainSigning`. That folder is never committed, and it must be backed up: without it, installed apps can't be updated. To build from this source without that key, the build falls back to your local debug key. Those APKs install fine on their own, but they can't update apps installed from these releases.
