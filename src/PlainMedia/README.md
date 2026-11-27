# Plain Media (Music + Youtube)

Formerly Plain Youtube. Since 3.0 one app with three tabs. It keeps Plain Youtube's package (`app.plain.download`),
so installed copies update in place with their follows, history and caches. The former Plain Music / LocalMusic
(`app.localmusic`) hands over its playlists, history, queue and settings once.

| Module | What | Was |
|---|---|---|
| `app` | Application (tab list), launcher icon | — |
| `music` | Music tab ([music/README.md](music/README.md)) | Plain Music / LocalMusic (`app.localmusic`) |
| `youtube` | Youtube tab ([youtube/README.md](youtube/README.md)) | Plain Youtube |
| `:shared` | `../PlainShared` | — |

The app now holds the INTERNET permission (for the Youtube tab); the Music tab's code still never opens a
connection. "Show in Music" in the Youtube tab opens the Music tab. Notification ids/channels: Youtube 1–3 and
"downloads"/"playback", Music 11 and "music_playback", Spotify 21 and "spotify_playback". The debug build installs beside the real app as
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



## Spotify playlists (3.2; 3.4 without sign-in)

There is no Spotify tab: Spotify refuses browser playback for the user's Free account (traced 2026-11-27, also in PC
Chrome), so the 3.2 web-player tab was dropped. Instead the Youtube tab's Search page has a **Spotify** chip
(Feed / Search / Lists / Spotify): type a Spotify profile link or username (or a playlist link), Find reads its
public playlists, pick which to convert, and each song is matched on YouTube Music. No Spotify account (3.4). See [youtube/README.md](youtube/README.md#spotify-playlists-32).

## 3.3

- Cached and downloaded are one library: a completed stream is saved like a download, a video is saved once per
  kind, and the player plays the saved copy. The old `Cached/` folders are merged once when the Youtube tab opens.
- "Continue from last session?" in both tabs.
- Battery: the cache migration moved from every app start to the Youtube tab; nothing else of the Youtube tab runs
  unless the user taps (it already stopped its requests when hidden).

## 3.4

- YouTube playlists loaded only their first 100 videos (so Download all / Cache all stopped at ~100): a playlist
  answer now carries a second continuation for a "more playlists" shelf, and the parser kept that one. It now keeps
  the continuation that closes the result list (`Browse.page`, test `playlistKeepsItsOwnContinuationNotTheShelfOne`).
- Spotify: the sign-in is gone. Type a profile link / username (or a playlist link); its public playlists are read
  from Spotify's public pages and converted & synced as before.
