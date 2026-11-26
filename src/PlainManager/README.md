# Plain Manager (Setup + Battery + Uninstaller)

Formerly Plain Setup. Since 2.0 one app with three tabs. It keeps Plain Setup's package (`app.plain.setup`), so
installed copies update in place, and it still answers every Plain app's "Check for updates"
(`app.plain.setup.UPDATE`). Its release asset keeps the name `PlainSetup.apk`, so old links keep working.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list), launcher icon | — |
| `setup` | Setup tab: install/update/roll back Plain apps (`assets/` holds the bundled APKs and apps.json, written by publish.py) | Plain Setup |
| `battery` | Battery tab (Android 10+, hidden on older phones) | Plain Battery (`app.plain.battery`) |
| `uninstaller` | Uninstaller tab (Shizuku 13.1.5, the one library) | Plain Uninstaller (`app.plain.uninstaller`) |
| `:shared` | `../PlainShared` | — |

Notes:
- Battery tracking is off until the Battery tab is opened once (so updating Setup to Manager starts nothing in the
  background). Its adb grants are per package: run them again for `app.plain.setup` (the tab shows the commands).
- apps.json entries with `merged_into` are former apps: listed only while installed, with their one hand-over
  update and a Remove button. The app they moved into is pre-ticked and says which old app it replaces.

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

