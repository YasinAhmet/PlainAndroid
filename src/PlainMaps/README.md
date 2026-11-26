# Plain Maps (Istanbul + Ankara + Trekking)

Formerly Plain Istanbul Map. Since 3.0 one app with three tabs. It keeps Plain Istanbul Map's package
(`app.plain.maps`) and paths (files/data.db, prefs "app"), so the synced Istanbul data carries on after the update.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list, per-process setup), launcher icon | — |
| `citymap` | Istanbul tab (`MainActivity`, main process) and Ankara tab (`AnkaraActivity`, `:ankara` process) | the istanbul/ankara product flavors |
| `trekking` | Trekking tab, `:trail` process ([trekking/README.md](trekking/README.md)) | Plain Trekking (`app.trekmap.trail`) |
| `:shared` | `../PlainShared` | — |

- **Processes.** `City.id` is set once per process by `MapsApp`; Ankara's data lives in files/ankara/, cache/ankara/,
  prefs "app_ankara", log sync-ankara.log. Trekking runs apart so a big city map can never take a recording down.
- **First visit.** A city tab without data asks once whether to sync now (nothing downloads unless the user says so).
- **Trekking data.** The former Plain Trekking hands over plans, tracks, photos, offline map packs and settings once;
  its prefs "app" become "trail" (Istanbul owns "app"). Ankara has nothing to import (it was never released).
- Plain Trekking's photos still go to Plain Camera (IMAGE_CAPTURE named at `app.plain.camera`).

## How the tabs work

- The launcher icon opens `HubActivity` (from `../PlainShared`), which forwards to the last used tab (Istanbul first).
- Each tab is its own Activity in its own task; the dropdown header on top switches tabs.
- Resources carry a prefix per module (`map_`, `trail_`) so they don't collide in one APK.

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

The APK is `app\build\outputs\apk\release\app-release.apk`. Release through `A:\Projects\PlainAndroid\publish.py`.
The city map's own documentation (data sources, real-data tests) is in [citymap/README.md](citymap/README.md).
