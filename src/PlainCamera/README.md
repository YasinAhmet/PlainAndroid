# Plain Camera (Camera + Gallery)

Since 2.0 one app with two tabs. It keeps Plain Camera's package (`app.plain.camera`), so installed copies update
in place. The former Plain Gallery (`app.plain.gallery`) hands over its categories and settings once.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list), launcher icon | — |
| `camera` | Camera tab (`app.plain.camera.CameraActivity`) | Plain Camera |
| `gallery` | Gallery tab and viewer (`app.plain.gallery.*`) | Plain Gallery (`app.plain.gallery`) |
| `:shared` | `../PlainShared`: tabs header, import, languages, updates | — |

A capture requested by another app (IMAGE_CAPTURE, e.g. Plain Maps' Trekking photos) shows the camera without the
tab header. The camera's last-shot thumbnail opens the Gallery tab's viewer.

## How the tabs work

- The launcher icon opens `HubActivity` (from `../PlainShared`), which forwards to the last used tab.
- Each tab is the former app's Activity, in its own task (`taskAffinity`), so it keeps its own state, background
  work and "Open with" intents. The dropdown header on top (`Hub.wrap`) switches tabs.
- A tab whose former app is still installed shows the import screen first (`Hub.gate` / `ImportActivity`):
  Copy / Start fresh / Not now. The former app's last release carries `MigrateProvider` (see `../PlainShared/bridge`).
- Each tab module's resources carry a prefix (`cam_`, `map_`, …) so they don't collide in one APK.

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

The APK is `app\build\outputs\apk\release\app-release.apk`, signed with the shared Plain key
(`A:\Projects\PlainSigning`). Release through `A:\Projects\PlainAndroid\publish.py`.

The former apps' own documentation follows ([tabs-README.md](tabs-README.md)); it still describes each tab.
