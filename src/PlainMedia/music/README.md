# LocalMusic

Offline music player for Android that plays **only local files**. Built for hiking with Bluetooth headphones
and airplane mode on: no network code, no `INTERNET` permission, no polling.

- Kotlin, plain Android framework (Views + Canvas, `android.media.session.MediaSession`, `MediaPlayer`).
  **No AndroidX, no media3, no third-party libraries** — the release APK is ~265 KB.
- minSdk 30 (MediaStore consent dialogs), target 35. Package `app.localmusic` (debug build: `app.localmusic.dev`).

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

The release APK (`app/build/outputs/apk/release/app-release.apk`) is minified and signed with the local debug key.
`local.properties` must use forward slashes (`sdk.dir=C:/Users/.../Android/Sdk`).

## Features

- Four tabs (the last one is remembered), search from everywhere in the top bar:
  - **Home**: shelves that scroll sideways, each with ▶ (play all) and "See all"; empty ones are hidden.
    **New pieces** (added in the last 30 days, newest first; 3+ songs of one album or channel that arrived within 12 h
    are one card), **Recently listened**, **Most played** (once 3+ songs have 2+ plays), **Videos** (latest),
    **From Plain Youtube** (latest saved and cached), **Playlists**.
  - **Library**: segments Songs · Artists · Albums · Genres · Folders, with small groups packed:
    - Artists: artists with 1 song (or ≤2, the "Pack" pill) and Unknown Artist are one "Singles & one-offs" entry at the
      top (songs sorted by artist). Sort by most songs or A–Z.
    - Albums: single-track albums of all artists are one "Singles" tile; same-named albums are merged across folders,
      composers and editions ("Romantizma" + "Romantizma 2015 (Re-Mastered)"; "Enderal OST" by five composers = one album).
    - Genres: genres with fewer than 3 songs are packed into "Other".
    - Folders: a collapsed tree. Roots: Music, Download, Plain Youtube, Plain Youtube · cached, then other roots.
      A folder holding only one subfolder is merged into one row ("Music › Albums › Tarkan"); two or more single-file
      subfolders are packed into "Loose files". Rows show folders, songs and duration.
    - Artist page: Play all / Shuffle all (merged), album grid (single tracks packed into "Singles"), then all songs.
  - **Videos**: Plain Youtube's videos per channel (newest channel first), VIDEO/CACHED badges, "Picture: on/off" pill
    (the same setting as the Video toggle in now playing).
  - **Playlists**.
- Listening history (`files/history.tsv`: id, path, last played, count; at most 2000 songs). A play counts after 30 s or
  half the song, whichever is first. No timer: listening time is summed from `elapsedRealtime` when playback starts,
  pauses or the song changes, and checked at pause / song change / song end. ⋮ → Clear listening history.
- The groupings (packed artists/albums/genres, folder tree, new pieces, videos per channel) are computed once per library
  load and cached in `LibraryIndex`; the list builds only the rows on screen, so art loads only for visible shelves.
  - Songs without artist tags get their artist from the folder (display only, the files are not changed):
    walk up from the file's folder, skip generic folders (Music, Download(s), PlainDownload, LocalMusic, WhatsApp/Telegram
    Audio, Bluetooth, Audio, Sounds, Recordings, Unknown, numbers, "CD 1", dated dumps...), take the first other folder;
    "X - Topic" (YouTube) becomes "X". If that folder is clearly an album (a year: "Karma (2001)", "2001 - Karma"; or OST,
    Soundtrack, Best Of, Vol. 2...), "Artist - Album (Year)" gives its artist part, else the folder above it is the artist.
    Inside `Music/LocalMusic/` the `<Artist>` level is used. Nothing usable left: "Unknown Artist".
    `Music/PlainDownload/Some Channel/x.m4a` → Some Channel · `Music/Tarkan/Karma (2001)/x.mp3` → Tarkan ·
    `Music/Tarkan/Karma/x.mp3` → Karma (not clearly an album) · `Download/x.mp3` → Unknown Artist. Details shows "(from the folder)".
- Search across title, artist and album (accent/Turkish-insensitive: "tanri" finds "Tanrı").
- **Cover art** (low resolution, embedded art via MediaStore thumbnails): album tiles try the album's songs in track
  order, first cover wins; artist rows use their most-played album (play history; else the biggest album), then one
  song per other album; "Singles", "Singles & one-offs", Home bundles and Folders rows show a 2×2 mosaic of 4 different
  covers (one cover if fewer); video rows show the MediaStore video thumbnail. Placeholder only when nothing has art.
  Art loads only for rows on screen, off the main thread, into one 12 MB LRU (mosaics are built from quarter-size
  thumbnails, RGB_565). When a file is rewritten in place (Plain Youtube embedding art), its size or DATE_MODIFIED
  changes; the next library load drops its cached art (and all mosaics), so the new cover shows without a restart.
- **Tags vs. folders**: artists and albums are grouped by a key without case, spaces or punctuation ("AC/DC" tag =
  "AC_DC" folder; "Back: In/Black" = "Back_ In_Black"). A Plain Youtube file whose artist tag is its channel keeps the
  channel whole ("Rock, Paper" is not split into "Rock"). So backfilled tags never split an artist or album in two.
- **Erase** (hold menus): a song or video ("Erase…"), an album (tile or page), an artist (row or page), the
  "Singles & one-offs" pack and the "Singles" tile ("Erase all in…", asked twice), genre songs, folder contents and
  "Loose files", Home cards and new-piece bundles, and "Erase songs in playlist (files)…" (separate from
  "Delete playlist (keeps songs)"). Every erase shows the count and size, then sends **one**
  `MediaStore.createTrashRequest` for the whole set: files go to Android's trash (restorable ~30 days, then Android
  deletes them). Erasing the song that plays skips to the next one.
- **Bulk erase**: hold a song → Select; tap rows to toggle; the bar offers All, Play next, Add to playlist, Erase N.
- **Media management** (MANAGE_MEDIA, optional special access, Android 12+): without it Android shows one consent
  dialog per erase/restore/delete; with it there is no dialog and an 8 s Undo bar appears instead. LocalMusic offers the
  Settings page once after the first erase, and in ⋮ → Media management.
- **Trash** (⋮ → Trash): "Trash (N · size)" lists trashed audio and Plain Youtube videos with days left
  (`DATE_EXPIRES`); select / All, Restore, Delete now, Empty trash (permanent delete = `createDeleteRequest`, consent
  unless Media management is granted).
- **Low storage** (checked on open/resume only, no background job): under 3 GB free with something in the trash, a
  banner "Low storage — trash holds 2.3 GB · Empty trash" opens the Trash. With Media management granted, LocalMusic
  instead deletes the oldest trashed items for good until 3 GB are free, and says so in a toast.
- **Sync with Plain Youtube** goes through MediaStore only: what either app trashes, restores or deletes shows up in the
  other on its next generation reload (resume / while visible). Vanished files drop out of the queue, Home shelves,
  history and playlists (restored ones come back, matched by path); a playing file that vanishes plays on while open
  and is skipped afterwards.
- Playlists: create, rename, delete, reorder (drag ≡), add/remove songs. Stored in `files/playlists.json`;
  entries keep both MediaStore id and path so they survive moves and rescans.
- Queue (drag to reorder, remove, clear upcoming), Play next / Add to queue, shuffle, repeat off/all/one.
- Gapless playback (`MediaPlayer.setNextMediaPlayer`).
- Seek slider with elapsed and remaining time (seeks on release); mini player; full now-playing screen with album art.
- Sleep timer (minutes or end of song).
- **Player controls on par with Plain Youtube (1.9)**:
  - **Speed** 0.5–2× (pitch kept, `PlaybackParams`): pill between the −10 s / +10 s buttons in now playing, also in ⋮.
    Remembered. A paused player gets the new speed when it next plays (setting it would start playback). At a speed other
    than 1× the next song is not pre-started gaplessly (a pre-started player would begin at 1×); it starts right after.
  - **−10 s / +10 s** everywhere: buttons in now playing; **double-tap** the left/right half of the art or video picture
    (short "« 10 s" mark); notification ⏮ −10 s ▶/❚❚ +10 s ⏭ ✕ (compact: ⏮ ▶ ⏭); on Android 13+ the lock screen and quick
    settings player show ±10 s as session custom actions next to prev/play/next; headset fast-forward / rewind keys.
    +10 s stops 1 s short of the end. A notification button after the process was reclaimed works (the library is loaded first).
  - **Close** (✕) in the notification stops playback and removes the notification (the queue stays).
  - **Volume**: speaker icon + bar for the media stream in now playing (`setStreamVolume(…, 0)`, no system panel); the
    speaker mutes and brings back the remembered level. The bar follows the hardware keys through a
    `VOLUME_CHANGED_ACTION` receiver registered only while now playing is shown and the app is in front. The volume keys
    change media volume anywhere in the app.
  - **Picture-in-picture** for videos (only while the picture is on screen): PiP button in now playing's top bar; actions
    −10 s, play/pause, +10 s (the notification's broadcasts). Closing the PiP window pauses (the queue stays); screen off in
    PiP drops the picture and plays on as audio, like the background. **⋮ → Picture-in-picture on Home** (off by default):
    `setAutoEnterEnabled` on 12+, `onUserLeaveHint` on 11, only while a video's picture plays. Overlays (queue) hide in PiP.
  - **Fullscreen** of a landscape video turns the screen to landscape (sensor, either way round); leaving it restores.
  - Fling down on the art closes now playing (the art now takes taps for the gestures above).
- "Open with LocalMusic" for local audio files (File Manager, WhatsApp voice notes): `VIEW` filter for `audio/*`,
  `application/ogg`, `application/x-flac` with **only** `content://` and `file://` schemes (no http). An opened file that
  isn't in the library plays once and then returns to the previous song, paused, leaving the queue untouched; a library
  song opened this way plays next in the queue. Unreadable files leave playback alone ("Can't play this file").
- Remembers queue, song and position.
- New files appear by themselves: the library reloads when `MediaStore.getGeneration` changed, checked in `onStart`/`onResume`
  and, only while the app is visible, by a debounced `ContentObserver` (registered in `onStart`, removed in `onStop`).
- **Plain Youtube** (`app.plain.download`) integration. Its files are untagged (title = file name) and its folders are
  read as they are, before the generic folder walk:
  `Music/PlainDownload/[Cached/]<Channel>/[<Album>/]<Title>.m4a` and `Movies/PlainDownload/[Cached/]<Channel>/<Title>.mp4` →
  first folder = artist (" - Topic" stripped), second folder = album (no year/keyword needed), no album folder = single
  under the artist. Tags win when present. Examples: `Music/PlainDownload/Tarkan - Topic/Karma/x.m4a` → Tarkan · Karma;
  `Music/PlainDownload/Some Channel/x.m4a` → Some Channel · (single); `Music/PlainDownload/Cached/Ch/Album X/x.m4a` → Ch · Album X;
  `Movies/PlainDownload/Cached/Ch/x.mp4` → Ch (video).
  - Fully cached items (`…/PlainDownload/Cached/…`) are normal library items with a CACHED mark. Plain Youtube may evict them;
    the generation check reloads the library, and queue/playlist entries whose file is gone are skipped.
  - Folders tab shows its folders as "Channel › Album" ("Cached › Channel › Album"), sorted under each other, cache last.
- **Videos**: Plain Youtube's videos (`Movies/PlainDownload/[Cached/]<Channel>/*.mp4`, only that tree, never camera or other videos)
  are library items with a VIDEO badge, artist = channel. Same player, queue and MediaSession as audio. On Android 13+ this
  needs the optional "Videos" permission (asked with the first permission request, or ⋮ → Show Plain Youtube videos…; denied = audio only).
  Now playing has a remembered **Video** toggle: on, the picture is drawn in a `SurfaceView` (tap it for fullscreen, tap/Back to leave);
  off, or with the screen off / app in the background / now playing closed, the MediaPlayer has **no surface**, so no video
  decoder runs and the video plays as audio. A following video is not pre-prepared gaplessly while its picture is on screen
  (the prepared-next player never gets a surface). Tapping a video row opens now playing when the toggle is on.
- Foreground media service + MediaSession: lock screen / notification controls, Bluetooth and wired headset buttons
  (also after the app was closed, via a media-button receiver), audio focus (pause/duck), pause on headphone
  disconnect (`ACTION_AUDIO_BECOMING_NOISY`).

## Continue from last session (3.3)

Tapping a song or video that was left in the middle asks "Continue from last session?" (Continue / From the
beginning). Positions are kept per MediaStore id in prefs `resume` (`SongResume.kt`), written when the player
pauses, seeks or loads another song — no timer. Only between 15 s from the start and 15 s before the end; a song
played to the end is forgotten. Not asked for the song playing now, for "Play all", or on auto-advance.

## Power

- MediaPlayer's partial wake lock is held only while audio plays.
- No timers in the service: lock screen and Bluetooth get position + timestamp in `PlaybackState` and extrapolate.
- The progress ticker runs only while the activity is resumed and playing; listeners are removed in `onStop`.
- OLED screen: pure black everywhere nothing needs to be seen. Cards, chips, tabs, the mini player and dialogs are
  black with a 1 dp outline (#222/#333) instead of a grey fill; the selected tab/sort is an amber outline, not a lit
  pill. Text #E4E4E4, secondary #A6A6A6, labels (durations, track numbers, icons) #747474. Colours live in `C` (`Ui.kt`).
- The volume receiver exists only while now playing is on screen and the app is in front; PiP params are touched only in
  PiP or with auto-enter on. Speed and ±10 s add no timers.
- The noisy receiver is registered only while playing. The library is reloaded only when `MediaStore.getGeneration` changed.
- The foreground service runs only while playing (or paused for a moment by a call, so playback can come back). On a real
  pause it leaves the foreground and stops itself; the paused notification stays (swipe-able; its buttons are broadcasts,
  so they work even after Android reclaimed the process). Paused with no LocalMusic screen visible, the MediaPlayers are
  released too (play prepares again at the remembered position). No timer is involved. The manifest media-button receiver
  stays (costs nothing at rest).
- Video costs nothing unless a video is current, the Video toggle is on and now playing is on screen: no video views,
  surface callbacks or permission checks exist for audio playback. The screen is kept on only while the picture plays.

## Organizer (⋮ → Organize library)

1. Scans all audio in MediaStore, reads tags with `MediaMetadataRetriever` (file-name fallback: "NN - Artist - Title").
2. Hashes (MD5) only files whose sizes collide; also matches the same song by title + artist + length (±2 s, same or
   missing album) in another quality and keeps the best (lossless > bitrate > tagged > already organised).
3. Shows a **plan**: every move, every duplicate removal (with what is kept), and the files left alone
   (ringtones, notifications, recorder folders, voice notes, < 20 s). Each line can be switched off.
4. On confirm: duplicates → `MediaStore.createTrashRequest` (Android trash, 30 days; restore from
   ⋮ → Trash), moves → `MediaStore.createWriteRequest` + update of `RELATIVE_PATH`/`DISPLAY_NAME`
   into `Music/LocalMusic/<Artist>/<Album>/NN Title.ext`. A merged album lives in one folder under its main artist.
   Files without artist tags go under the folder artist (`Music/PlainDownload/Some Channel/x.m4a` →
   `Music/LocalMusic/Some Channel/Singles/x.m4a`). Re-runs are stable: untagged files keep their artist/album folder
   (including `Unknown Artist/<folder>/` from older versions), "Song (2).mp3" stays. Videos are never touched.
   Plain Youtube's tree is left alone: `Music/PlainDownload/Cached/` is never moved or trashed (not even when ticked; Plain
   Youtube owns it), and the rest of `Music/PlainDownload/` is listed as "left alone" by default (already sorted per
   channel/album; moving breaks its Saved marks) and is not part of duplicate detection unless ticked.
5. Writes a report and a `moves-*.tsv` (id, old path, new path) to `Android/data/app.localmusic/files/`.

Empty source folders are left behind (MediaStore cannot remove directories).

## Code map

| File | What |
|---|---|
| `Text.kt`, `Naming.kt`, `Plan.kt`, `PlayQueue.kt`, `Model.kt`, `Sections.kt` | Pure logic (unit-tested in `LogicTest.kt`): folder artist, video classification, packing, folder tree, new pieces, play history |
| `Library.kt` | MediaStore query, album-art cache |
| `Player.kt` | Engine: MediaPlayer, queue, session, focus, sleep timer, speed, ±10 s |
| `Controls.kt` | Pure logic for the player controls (unit-tested in `ControlsTest`): speeds, ±10 s target, volume/mute, PiP ratio |
| `PlaybackService.kt` | App class, foreground service + notification, receivers |
| `MainActivity.kt`, `NowPlaying.kt`, `Ui.kt` | UI |
| `Organizer.kt`, `OrganizerView.kt` | Organizer engine, plan screen, trash screen |

## Share (1.10)

The song menu (long-press a song, or its menu in Now playing) has **Share file…** / **Share video…**: Android's share sheet gets the file itself (its MediaStore `content://` URI with read access granted to the chosen app). LocalMusic still has no internet permission; the app you pick does the sending. Files another app handed over as `file://` can't be shared this way and don't get the entry.
