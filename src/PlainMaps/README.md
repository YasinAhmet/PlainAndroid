# Plain Maps (city tabs + Trekking)

Formerly Plain Istanbul Map. Since 3.0 one app with three tabs. It keeps Plain Istanbul Map's package
(`app.plain.maps`) and paths (files/data.db, prefs "app"), so the synced Istanbul data carries on after the update.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list, per-process setup), launcher icon | — |
| `citymap` | City tabs: Istanbul (`MainActivity`, main process); Ankara, İzmir, Kocaeli, Antalya (`AnkaraActivity` … in `:ankara`, `:izmir`, `:kocaeli`, `:antalya`) | the istanbul/ankara product flavors |
| `trekking` | Trekking tab, `:trail` process ([trekking/README.md](trekking/README.md)) | Plain Trekking (`app.trekmap.trail`) |
| `:shared` | `../PlainShared` | — |

- **Processes.** `City.id` is set once per process by `MapsApp`; every city but Istanbul keeps its data in files/<id>/,
  cache/<id>/, prefs "app_<id>", log sync-<id>.log. The cities are one table, `City.all` (since 3.6).
- **Tab sheet.** Only downloaded cities (files/…/data.db exists) and Trekking are listed; the others sit behind an
  "Others ›" row (`Tab.shown` in PlainShared's Hub). Trekking runs apart so a big city map can never take a recording down.
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
