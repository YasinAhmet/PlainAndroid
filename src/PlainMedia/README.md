# Plain Media (Music + Youtube + Spotify)

Formerly Plain Youtube. Since 3.0 one app with three tabs. It keeps Plain Youtube's package (`app.plain.download`),
so installed copies update in place with their follows, history and caches. The former Plain Music / LocalMusic
(`app.localmusic`) hands over its playlists, history, queue and settings once.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list), launcher icon, Spotify placeholder tab | — |
| `music` | Music tab ([music/README.md](music/README.md)) | Plain Music / LocalMusic (`app.localmusic`) |
| `youtube` | Youtube tab ([youtube/README.md](youtube/README.md)) | Plain Youtube |
| `:shared` | `../PlainShared` | — |

The app now holds the INTERNET permission (for the Youtube tab); the Music tab's code still never opens a
connection. "Show in Music" in the Youtube tab opens the Music tab. Notification ids/channels: Youtube 1–3 and
"downloads"/"playback", Music 11 and "music_playback". The debug build installs beside the real app as
`app.plain.download.dev`.

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

