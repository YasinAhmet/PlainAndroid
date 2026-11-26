# Plain Youtube

(Formerly "Plain Download". Renamed in 1.3; the package stays `app.plain.download`, so updates install over the old
app with its history, follows and caches, and the public folders stay `Music/PlainDownload` and `Movies/PlainDownload`.)

Android app that searches, plays and saves YouTube audio and video from a pasted or shared link for **personal offline use** (hikes,
flights), like the free YouTube-download websites do, but on the phone and without ads or trackers.

- Kotlin, plain Android framework (`HttpURLConnection`, `MediaExtractor`, `MediaMuxer`, `MediaStore`, Views).
  **No AndroidX, no third-party libraries, no yt-dlp/ffmpeg/NewPipe.** The release APK is ~210 KB.
- minSdk 30, target 35. Package `app.plain.download` (debug build: `app.plain.download.dev`).
- OLED style shared with LocalMusic: pure black, 1 dp #333 outlines, text #E4E4E4 / #A6A6A6 / #747474, amber accent
  #E6B422. Colours live in `C` (`Ui.kt`).

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

The release APK (`app/build/outputs/apk/release/app-release.apk`, copied to `PlainDownload.apk`) is minified and signed
with the local debug key. `local.properties` must use forward slashes (`sdk.dir=C:/Users/.../Android/Sdk`).

## Features

- Two tabs at the top: **Download** (paste a link, options, the downloads list) and **Search** (below). The last tab
  is remembered.
- Paste a link (Paste button or keyboard) or **Share → Plain Download** from the YouTube app or a browser
  (`ACTION_SEND text/plain`). Understood: `youtube.com/watch?v=`, `m.`/`music.`/`www.` hosts, `youtu.be/`, `/shorts/`,
  `/embed/`, `/live/`, youtube-nocookie, a bare 11-character id, and a link inside a longer shared text.
  A **channel** link (`/@handle`, `/channel/UC…`, `/c/…`, `/user/…`) or **playlist** link (`list=…`) opens that page in
  the Search tab (a `watch?v=…&list=…` link is still treated as the single video).
- Info card: thumbnail, title, channel, duration, the real formats YouTube offers.
- **Audio** — saved without re-encoding:
  - **M4A (AAC)**: itag 140 (~128 kbps, "Best") or 139 (~48 kbps HE-AAC, "Small"), remuxed from YouTube's DASH
    fragmented MP4 into a clean `.m4a` with `MediaExtractor → MediaMuxer(MUXER_OUTPUT_MPEG_4)`.
  - **Opus (.ogg)**: itag 251 (~130–160 kbps) or 249/250 (~50–70 kbps), remuxed from WebM into Ogg with
    `MediaMuxer(MUXER_OUTPUT_OGG)` (Android 10+).
  - It is labelled as M4A/Opus on purpose: **no MP3**. Android has an MP3 decoder but no MP3 encoder, and there is no
    tiny framework-only way to make one (a pure-Kotlin encoder such as a port of `shine` would be thousands of lines,
    plus a full decode → encode pass that costs battery and quality). Every current player, LocalMusic included,
    plays M4A and Opus.
- **Video (MP4)**: lists the heights that really exist (144p … 1080p, plus 60 fps variants).
  - If the height has a progressive stream (itag 18, 360p H.264+AAC) it is saved as-is.
  - Otherwise the H.264 (avc1) video-only DASH stream + the AAC audio stream are downloaded and merged with
    `MediaMuxer(MUXER_OUTPUT_MPEG_4)`, without re-encoding.
  - H.264 is always preferred. **AV1** is offered only for heights above the best H.264 (1440p/2160p) and only on
    Android 14+, where the platform MP4 writer knows `av01`. **VP9 is never offered**: AOSP's `MPEG4Writer` has no VP9
    support, so it can't go into an MP4 with `MediaMuxer`.
- Remembers the last choices (audio/video, format, quality, height; the closest lower height is used when the
  remembered one doesn't exist).
- Downloads list (history in `files/history.json`, last 200): progress, Cancel, Retry (resumes partial streams),
  Remove, Open, Show in Music (opens LocalMusic if installed). Long-press for more.
- Clear errors for age-restricted, members-only, private and live videos (no sign-in support, on purpose).

## Tags and cover art in the files (1.9)

Plain Music has no internet and reads art only from the files (MediaStore), so the art is **embedded** (no loose
cover.jpg in Music/, which would show up in the gallery).
- **M4A:** `moov/udta/meta/ilst` with `©nam` (title), `©ART` and `aART` (= the channel folder name), `©alb` (album when
  known) and `covr` (JPEG). Other items (YouTube's `©too`) are kept; re-tagging replaces instead of stacking. Only `moov`
  is rewritten (`Tags.kt`): YouTube's DASH M4A has no absolute offsets after it (empty `stco`, `tfhd`
  default-base-is-moof without base_data_offset, relative `sidx`), so growing moov is safe; a regular MP4 with moov
  before its media (MediaMuxer can reserve room at the start) gets every `stco`/`co64` offset shifted; moov at the end
  moves nothing.
- **Opus (.ogg):** the OpusTags packet gets `TITLE`, `ARTIST`, `ALBUMARTIST`, `ALBUM` and `METADATA_BLOCK_PICTURE`
  (base64 FLAC picture block, front cover); it is re-paged (a big picture spans several pages), later pages are
  renumbered with fresh CRCs. If a file's layout is unusual (OpusTags sharing a page with audio), it is saved untagged.
- **Verified on the PC** (PyAV + mutagen in a throwaway venv, real files): fragmented itag 140, a moov-at-start and a
  moov-at-end MP4, and Ogg Opus with a 2 KB and a 390 KB picture — decoded audio identical to the originals
  (same sample count and MD5), same duration, seeks to 30/120/200 s land correctly, and mutagen reads title / artist /
  album / cover.
- **Art source:** the video thumbnail's centre square (4:3 thumbnails are letterboxed 16:9, so the bars go first;
  YouTube Music tracks are square artwork in the middle of the frame, so the result is the album cover).
  **⋮ → Cover art: None / Low / High** (default Low). Low = `hqdefault` → 270 px, JPEG q80, ≈ 14–21 KB; High =
  `maxresdefault` (else `sddefault` / `hqdefault`) → up to 720 px, q85, ≈ 50–130 KB. None writes no art and leaves
  existing art alone. Wi-Fi only applies. Video MP4s get no `covr` (MediaStore makes frame thumbnails for them).
- **New downloads and cache publishing:** the art is fetched in the download stage (≤ 10 s; otherwise the file is
  saved without it) and written during the copy/convert step: for original-format M4A it's one streaming copy that
  inserts the new moov; "saved in" includes it.
- **Backfill:** ⋮ → "Add missing cover art…" checks every saved and public-cached audio file of ours (reads each file's
  header; files Plain Music moved are found by id), then offers one batch "Add cover art" in the downloads list
  (low priority, progress, Stop, Wi-Fi only). Each file is read from MediaStore, tagged into a copy in app storage and
  written back through the "rwt" descriptor. Files that aren't ours are skipped. Offered once on the first start of
  1.9, and again when Cover art goes from None to Low/High.

## Erase, trash and sync with Plain Music (1.8)

- **Erase = Android's MediaStore trash** (`IS_TRASHED=1`): restorable, Android deletes it for good about 30 days later
  (`DATE_EXPIRES`). Nothing is deleted at once unless you pick "Delete now" or "Empty trash". The app created these
  files, so it trashes them without a consent dialog and shows an **Undo** bar for 8 s; files that aren't ours any more
  (e.g. after a reinstall) go through one system dialog for the whole set (`createTrashRequest` / `createDeleteRequest`).
- **Where:** downloads-list rows (hold → Erase; trashed rows show "In trash" with Restore / Delete now), batch rows (hold →
  "Erase all files of this batch"), **selection mode in the downloads list** (hold → Select, or a batch row's Select;
  bar: N selected · All · Erase · ⋮ = Erase, Restore, Retry failed, Cancel, Remove from history), Cached-folder rows
  (Erase sends the public cached file to the trash; the LRU still deletes cached files for good), channel pages
  (Download all… → "Erase downloaded (N)…", matched by video id and by the channel folder, Album subfolders included),
  album / playlist pages ("Erase downloaded…").
- **Trash view:** ⋮ → "Trash (N · size)": our trashed files under `Music/PlainDownload/` and `Movies/PlainDownload/`
  (`QUERY_ARG_MATCH_TRASHED`), each with days left, Restore and Delete now, plus Empty trash.
- **Low space:** when the app opens or resumes and free storage is below 3 GB, our oldest trashed files (soonest
  `DATE_EXPIRES`) are deleted for good until it is above 3 GB again or the trash is empty ("Freed 1.2 GB from trash").
  Only our own files; this runs before any cache eviction. No background job.
- **Sync** (Plain Music, a file manager or Android itself may change the files): on open/resume, one MediaStore query
  by id (trashed rows included) for every saved file. Trashed elsewhere → the row says "In trash" with Restore and the
  ✓ Downloaded mark is cleared (so it can be downloaded again); deleted for good → "Removed" (the row stays until you
  remove it); restored → "Saved" with the mark again; moved / renamed (e.g. Plain Music's organizer) → the row's path
  follows. Public Cached entries that were trashed or deleted elsewhere leave the Cached folder; moved ones follow.
  Pure logic in `Trash.kt` (`Reconcile`, `Purge`), unit-tested.

## Music: Topic channels, artists, albums, batches (1.6)

- **What Topic channels return** (checked live with "Botho Lukas Chor - Topic", `UCqEZVN5SnfNGz6yD7UWOJRw`, and
  "Luis Fonsi - Topic"): on YouTube itself only a **Home** tab with one "Albums & Singles" shelf (album lockups,
  `LOCKUP_CONTENT_TYPE_ALBUM`, the rest behind an engagement panel) — no Videos tab, which is why the channel looked
  empty. The complete picture is YouTube Music's artist page: WEB_REMIX `browse` of the same UC id lists "Albums" and
  "Singles & EPs" (each with a "More" discography page `MPAD<UC…>` + params that lists every release), "Top songs"
  (its "Show all" is a playlist `OLAK5uy_…` with all the artist's songs: 42 for Botho Lukas Chor, 150 for Luis Fonsi)
  and "Videos" (also a playlist). Every release is an album playlist (`OLAK5uy_…`) that opens like any playlist.
- **Music tab** on channel pages, next to Videos / Shorts / Playlists: "All songs", "Music videos", then every album,
  single and EP (cover, title, type, year). First (and default) on Topic channels; on other channels it appears only when
  YouTube Music lists releases for the channel id (checked once in the background after the channel loads). Tapping a
  release opens it as a playlist page with Play all, Listen all, Cache all and Download all.
- **Paging (1.7).** YouTube Music discography pages hold 100 releases each; the next page is
  `nextContinuationData.continuation` (in `gridRenderer.continuations`, then `continuationContents.gridContinuation`),
  requested as WEB_REMIX `browse` with `{"continuation": token}`. It only works with the visitorData of the session
  that produced the token ("Something went wrong" otherwise), so the app keeps YouTube Music's visitorData in memory
  and sends it with every WEB_REMIX request. Verified live: "Jan Sebastian Bach" (`UCFtSXTlIMFFkyJbHO3V5b7A`) Singles &
  EPs = 31 pages, 3,041 releases. The Music tab shows the first page of albums and of singles and a **Load more** at
  the end (albums first, then singles); All songs / Music videos open as normal playlists (100 per page, Load more).
  Download all → All music walks every page with progress and Stop and stops once a run's worth (500) of new tracks
  is collected; running it again continues. When a "More" page fails (Bach's albums "More" answers "Something went
  wrong" from YouTube Music), the artist page's carousel is used instead.
- **Download all everywhere.** Album / playlist pages: the whole list, its name as the album folder (Music app:
  Channel › Album). Channel pages: **Download all…** → "All music (every album & single)", "All videos" or
  "Everything": collects every list first (progress + Stop), then one batch where each video appears once (the first
  release it was found in gives its album folder; songs not on any release get their album from YouTube Music at save
  time). Already downloaded / queued videos are skipped and counted; at most 500 per run — the message says how many
  are left, and running it again continues with them. Hold menus: album rows → Open, Download all…, Cache all, Listen
  all, Add to list…; channel rows → Download all music…. On album pages, Topic channels and the Music tab, bulk
  downloads default to **audio** and Cache goes to the Music cache.
- **Batches.** Every bulk action (album, All music, playlist, Download N, Cache N) is one row in the downloads list:
  "Botho Lukas Chor · All music — 37/120 · 3 downloading · 1 converting · 2 failed" with a progress bar, Cancel all,
  Retry failed and Remove; tap it to see its tracks (each still its own file in Channel/Album). The notification shows
  the batch's progress with "Cancel all". History keeps the newest 30 batches (as a whole) plus 200 single entries.
- **Parallel audio.** Audio downloads run up to 3 at a time (⋮ → Parallel audio downloads, 1–4); a job with video runs
  alone. Still one converter at a time (with original-format M4A it's a copy). The free-space rule counts every
  running download's remaining size. The parallel downloads overlap each other's player requests, so there is no
  separate pre-resolver; visitorData is shared process-wide already.

## Saving, converting and the download pipeline (1.5)

- **Audio: original format (⋮, default on).** M4A is saved as YouTube's own file, copied as-is: the DASH
  fragmented MP4 (`.m4a`, `audio/mp4`, filed by MediaStore as audio). Checked on the PC: itag 140 starts with
  `ftyp` + `moov` whose `mvhd`/`mdhd` carry the full duration (e.g. 12417024 / 44100 = 281.6 s for a 4:41 track), then a
  `sidx` index (29 references) and `moof`/`mdat` fragments — so the media scanner and MediaPlayer get the duration from
  `moov` and can seek via `sidx`. Off = "Convert to clean M4A" (the old remux).
- **Opus** is always converted to `.ogg` (remux, no re-encoding): its original container is WebM, and Android's MIME
  map (AOSP `mime.types`) has `video/webm webm` and `audio/ogg oga ogg opus spx` but no audio-typed WebM extension
  (no `.weba`), so an original Opus file would be filed as a video. The info card says so.
- **Faster conversions.** Remux/merge now write into a temp file in `noBackupFilesDir` (plain app storage) and the
  result is copied into the MediaStore file in 1 MB blocks; before, MPEG4Writer's many small writes and seeks went
  through the MediaStore file descriptor (FUSE on Android 11+). The same applies to the Cached-folder publishing. Not
  measured on the phone (the PC has no FUSE); the measured case (a 1:37 h, 94 MB M4A took 2 min 12 s to remux) no
  longer converts at all with the default setting.
- **Progress and time.** While saving, the row and the notification show "Converting 37% · about 1:10 left" (sample
  time ÷ duration, ETA from a smoothed rate, updated at most once a second) or "Saving 37%" for a plain copy — never
  "Downloading 100%". Finished rows say "converted in 2:41" / "saved in 4 s".
- **Pipeline.** One downloader and one saver thread in the same foreground service: when a download finishes, the job
  goes to the saver ("Downloaded · waiting to convert" if another is converting) and the next download starts at
  once. At most one download and one conversion at a time; conversions run in order. Before each download the free
  space must cover the parts already waiting for conversion (their output) plus twice the next job's size plus 300 MB;
  otherwise it waits for the saver. Cancel works in every stage. The notification shows the active job and e.g.
  "1 downloading · 1 converting · 3 queued". The service and its wake locks stay until both workers are idle.
- **Restarts.** Only a job that was running becomes "Interrupted — Retry" (its parts resume); queued jobs stay queued
  and start when the app is opened (foreground, user-visible; still no background auto-start).

## Search tab

```
[Download] [Search]                         ⋮
[‹] (Feed) (Search)          <- while selecting:  ✕  12 selected  [All] [Download 12]
page: Feed | Search | Following | Channel | Playlist, each a list with "Load more" at the end
```

- **Search**: a text box + filter chips **Videos / Channels / Playlists** (InnerTube WEB `/youtubei/v1/search`).
  Pasting a channel or playlist link (or `@handle`) in the box opens that page; a video link opens the download
  options. "Load more" at the end of a list fetches the next page (continuation token); there is no auto-prefetch.
- **Channel page**: name, handle, subscribers, **Follow / Following**, sub-tabs **Videos / Shorts / Playlists**
  (`/youtubei/v1/browse` with the channel id + tab params). `@handle` / `c/` / `user/` URLs are resolved with
  `/youtubei/v1/navigation/resolve_url`.
- **Playlist page**: `/browse` with `VL<playlistId>`, 100 videos per page; **Download all** loads every page first
  (progress + Stop), then asks the options once.
- **Feed**: newest videos of the channels you follow, merged and sorted by date, from YouTube's public RSS
  (`/feeds/videos.xml?channel_id=UC…`, 15 newest per channel, parsed with `XmlPullParser`). Cached in `files/feed.json`
  so it shows at once, also offline. Opening the Feed fetches only channels whose RSS is older than **30 min** (or
  newly followed ones); **Refresh** fetches all. At most 4 RSS requests run at once. **Following (N)** lists the
  followed channels (tap = open, long-press = unfollow).
- **Follows, no Google login**: Google blocks sign-in inside embedded WebViews and the app doesn't pretend to be a
  browser, so subscriptions aren't read from the account. Instead channels are followed in the app
  (`files/follows.json`) and existing subscriptions can be imported once from **Google Takeout**:
  takeout.google.com → Deselect all → *YouTube and YouTube Music* → *All YouTube data included* → only
  *subscriptions* → export. Pick the `.zip` or `subscriptions.csv` (`Channel Id,Channel Url,Channel Title`; header row,
  quotes and BOM handled; localised Takeouts, e.g. `abonelikler.csv`, are found by content). Import… is in the Feed
  and in ⋮. Imports only add channels.
- **Rows**: thumbnail (`i.ytimg.com/vi/<id>/mqdefault.jpg`, only for rows on screen, RGB_565, 6 MB in-memory LRU),
  title, channel (tap = channel page), duration, views, age, and **✓ Downloaded** / **In the download queue** (by video
  id; `files/downloaded.txt` remembers every saved id + kind, beyond the 200-entry history). Tap a video = the normal
  download options for it in the Download tab (Back returns to Search).
- **Bulk**: long-press a video → selection mode (tap to add/remove, **All**, count in the top bar) → **Download N**
  asks once: Audio (M4A/Opus, Best/Small) or Video **best quality up to** a height (each video gets its best H.264
  stream at or below it; the remembered choices are reused). Live/upcoming videos and ids already downloaded as that
  kind (or already queued) are skipped and counted. At most 500 videos per bulk action.
- The queue still runs one job at a time. The downloads list shows running → queued (in download order) → finished,
  at most 50 rows until "Show all"; rows that didn't change aren't rebound. ⋮ has **Retry all failed** and
  **Cancel all queued**. If Wi-Fi drops during a long queue, the rest of the queue is marked "Not on Wi-Fi" at once
  instead of failing one by one. History keeps the newest 200 *finished* entries; queued ones don't count.

## Press & hold, selection, lists, play queue (1.4)

- **Hold a video row** (Search, Feed, channel, playlist, local list pages): Visit channel · Watch · Listen · Download… ·
  Add to play queue · Add to list… · Select (then taps toggle rows, like before). On a local list page also "Remove
  from this list". Channel rows: Open channel, Follow/Unfollow. Playlist rows: Open, Download all…. Local list rows:
  Open, Play all, Listen all, Download all…, Cache all, Rename…, Delete.
- **Downloads list rows**: Visit channel, Watch / Listen (a finished item: its saved file), Watch, Listen, Download…,
  Add to play queue, Add to list…, Open in another app, Open in Music, Retry / Cancel, Remove from history. (No
  selection mode in the downloads list.) **Cached folder rows**: Visit channel, Play (cached), Watch, Listen,
  Download…, Add to play queue, Add to list…, Save, Remove.
- "Visit channel" uses the row's channel id, or looks it up from the video (one player request). The player has a
  **Channel** button and the info card's channel line is tappable too.
- **Selection mode**: top bar ✕ · N selected · All · Download N · ⋮ (Download N…, **Cache N**, Add to play queue,
  Add to list…).
- **Cache N** = low-priority jobs in the download queue (they run after every normal download). Each fills the Music
  cache (videos whose remembered choice is Listen, or music detected like in the player) or the Video cache (HLS, best
  H.264 up to the Watch quality), exactly like watching would, then publishes to `…/PlainDownload/Cached/`. Limits,
  3 GB free space and Wi-Fi only apply; already cached / queued videos are skipped. Up to 60 consecutive network
  failures per job, then it fails with Retry.
- **Lists** (Search tab → **Lists** chip): local named lists (`files/lists.json`: id, title, channel, duration,
  thumbnail). Created from "Add to list…" → New list…. A list page has Play all, Listen all, Download all…, Cache all.
  Audio downloaded or cached from a list (or a YouTube playlist) uses its name as the album folder.
- **Play queue** (`files/queue.json`): Add to play queue appends; Play all / Listen all (lists, playlists) replace it.
  ⋮ → Play queue or Lists → Play queue opens the player on it: ⏮ / ⏭, auto-advance at the end of each item (each
  entry keeps its own Watch / Listen choice), **Queue** sheet with ▲ ▼ ✕ and tap-to-jump. Playback still stops when
  the player is left or the app goes to the background; the next item is not prefetched.

## Player: watch / listen in the app (and cache while doing it)

- **Where to tap**: the **thumbnail** plays (Search / Feed / channel / playlist rows, the Download tab's info card
  and every row of the downloads list); the title area keeps the download flow. In search rows **♪** plays audio only.
  A finished download plays its saved file (offline); a queued / running / failed one streams.
- **Watch / Listen** switch in the player (continues at the same position). The default per video: Listen for audio
  tracks / art-only uploads (`musicVideoType` ATV, from a small WEB_REMIX player call), "- Topic" channels,
  music.youtube.com links and other videos in the Music category (WEB player microformat); Watch otherwise. A manual
  choice is remembered per video (`Modes.decide`, unit-tested).
- Controls: play/pause, seek bar with times (cached part shown as the secondary bar), −10 s / +10 s, quality (Watch),
  Save, Download… (the normal options), Full screen (landscape + hidden bars). The player rotates with the sensor
  only while it is open; the screen stays on only while playing.
- **Streaming strategy** (verified live 2026-11-26):

  | Client | HLS master | itag 18 (360p muxed) | Adaptive streams |
  |---|---|---|---|
  | VISIONOS | yes (15 variants: H.264 + VP9, video-only TS) | no | full ranges OK |
  | IOS_PRODUCER | yes (7 H.264 variants) | no | full ranges OK (no Opus) |
  | ANDROID_VR | no | yes, but **403 past the first ~5 MB** (no PO token) | same 403 for every format |

  MediaPlayer can't merge separate DASH video + audio, and itag 18 can no longer be streamed past ~1 minute, so:
  - **Watch** = one HLS quality (H.264 variants 144p…1080p; VP9 ignored). YouTube's HLS has video-only MPEG-TS
    segments and a separate AAC audio rendition (`EXT-X-MEDIA TYPE=AUDIO`, packed ADTS with an ID3 timestamp).
    The proxy serves MediaPlayer a local master with exactly that variant + its audio group.
  - **Listen** = the audio stream only (itag 140 M4A; 251 Opus WebM if 140 fails or is missing), progressive with
    HTTP ranges. No video bytes are requested and no video surface is created.
  - If MediaPlayer can't play the HLS stream, the player switches to Listen for that session.
- **Local caching proxy** (`Player.kt`): an HTTP server on 127.0.0.1 (random port) exists only while the player is
  open. One background "filler" thread fetches from YouTube (Wi-Fi-bound with "Wi-Fi only", retries handshake
  resets for as long as the player is open, 403 -> fresh URLs from the client chain) and writes into the cache;
  MediaPlayer is served from the cache files. It fetches from where the player reads, then continues to the end
  even while paused, so the whole video/track gets cached. Seeks into cached parts are served from disk.
  Bookkeeping: byte ranges (Listen) or segment indices (Watch) in `meta.json` (`Ranges`, unit-tested).
  `network_security.xml` allows plain HTTP to 127.0.0.1 only.
- **Two caches**, **Video** (HLS segments) and **Music** (audio streams), each with its own LRU and limit
  (⋮ → Cache limits, default 20 GB each). While incomplete, an entry lives privately in the app's cache storage
  (`Android/data/app.plain.download/cache/stream/{video,music}`). Least recently played entries go first; caching
  stops reading ahead (and the partial entry is dropped on close) when free storage would fall below 3 GB.
- **Complete = public** (1.3): when a cached entry is complete it is remuxed / joined (no re-encoding, same code as
  Save) through MediaStore into
  - `Music/PlainDownload/Cached/<Channel>/[<Album>/]<Title>.m4a` (or `.ogg`) — so LocalMusic lists it, or
  - `Movies/PlainDownload/Cached/<Channel>/<Title>.mp4`
  and the private copy is deleted. This happens when the player closes (the player may still be reading the private
  copy before that), before an offline replay, and once at app start for older complete entries (migration, off the
  main thread). The cache index keeps the public uri; limits count the public file's size; replays play the public
  file offline. The app owns these files, so LRU eviction, Remove and Clear delete them without prompts; if Android
  refuses (e.g. after a reinstall the files aren't ours any more) the app asks once with the system trash dialog.
  A public cached file deleted elsewhere drops out of the index the next time the Cached folder or the player looks.
- **Cached folder** in the Download tab (below the list): "Cached · videos N (size) · music N (size)" opens the
  temporary folder with two groups, each with Clear; rows show thumbnail, title, channel, quality, the public path or
  "45% cached". Tap = play (offline when complete). **Save** moves a complete entry from `…/Cached/<Channel>/…` to
  the permanent `…/<Channel>/…` folder by updating RELATIVE_PATH (no copy), adds a history entry and removes it from
  the cache. An incomplete entry is handed to the normal download queue instead (it downloads fresh: the queue uses
  DASH streams, not HLS segments). In the player, Save on a complete but still private entry happens when the player
  closes.
- **Albums** (audio only, `Albums.pick`, unit-tested): the YouTube Music album for "- Topic" tracks (WEB_REMIX `next`:
  the byline run that links to an album page); otherwise the playlist the item was played or bulk-downloaded from
  (not automatic lists such as "Uploads from …" or "Mix - …"); otherwise no album folder.

## Where files go

A contract with LocalMusic (artist = channel folder, album = folder below it) — don't change it:

| What | MediaStore collection | Path |
|---|---|---|
| Saved audio | `MediaStore.Audio` (`IS_MUSIC=1`) | `Music/PlainDownload/<Channel>/[<Album>/]<Title>.m4a` (or `.ogg`) |
| Saved video | `MediaStore.Video` | `Movies/PlainDownload/<Channel>/<Title>.mp4` |
| Fully cached audio | `MediaStore.Audio` | `Music/PlainDownload/Cached/<Channel>/[<Album>/]<Title>.m4a` (temporary) |
| Fully cached video | `MediaStore.Video` | `Movies/PlainDownload/Cached/<Channel>/<Title>.mp4` (temporary) |

- Channel = uploader name with `" - Topic"` stripped (YouTube Music auto channels). A channel named exactly
  "Cached" (any case) becomes "Cached (channel)" so it can't clash with the cache root.
- The album level exists only when an album is known (see Albums above); video never has one.
- Names are made safe (`/ \ : * ? " < > |`, control characters, leading dots) and cut to 120 UTF-8 bytes.
- Never overwrites: `Title (2).m4a`, `Title (3).m4a`… (MediaStore also renames on a clash with other apps' files).
- Written through MediaStore with `IS_PENDING=1` while muxing, published at the end; a failed or cancelled file is
  deleted. No storage permission is needed (Android 10+ apps can create their own media entries).
- Partial streams live in `noBackupFilesDir/parts/<jobId>_<itag>_<size>.part` until the file is saved.

## How it gets the streams (and why it will break one day)

`Net.kt` / `Clients.kt` are a tiny InnerTube client: `POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false`
with a client context, no account, no cookies. Only clients that yt-dlp marks as needing **neither the JS player nor
a PO token** are used, because their stream URLs are direct (no `signatureCipher`, no `n` transform):

| Order | Client | Verified 2026-11-25 from a PC | Notes |
|---|---|---|---|
| 1 | `VISIONOS` 1.02 | player OK + stream bytes OK | yt-dlp's default JS-less client. All formats. Often answers "Sign in to confirm you're not a bot" without `visitorData`; the app retries once with the `visitorData` from that answer, which worked for every test video. |
| 2 | `ANDROID_VR` 1.65.10 | player OK + stream bytes OK (also itag 18) | Same bot check / fix. yt-dlp reports intermittent PO-token enforcement (403s) for this version. |
| 3 | `IOS_PRODUCER` 0.147.945 | player OK + stream bytes OK | H.264 + AAC only (no Opus, no itag 18). Worked even without `visitorData`. Added to yt-dlp in Nov 2026. |

Not used: `IOS` (0 direct URLs in the test, SABR only), `ANDROID` (only itag 18 direct), `TVHTML5` variants (need the
JS player; `tv_samsung` answered "The page needs to be reloaded"), `WEB*` (PO token).

Fallback: clients are tried in order; "not a bot", "unavailable" and network errors move on to the next client,
while age-restricted / members-only / private / live stop at once with a message. If googlevideo answers **403** to a
stream (refused or expired URL), the job asks the next client for fresh URLs and resumes the partial file.
`visitorData` is YouTube's pseudonymous visitor id; it is kept in memory only.

Downloads use `Range: bytes=a-b` requests in **10 MB chunks** (like yt-dlp's `http_chunk_size`; long single requests
get throttled), reuse the keep-alive connection, and retry failed handshakes/reads up to 60 times per chunk. That
retry matters: in the PC test the ISP-hosted Google cache node reset 85–97 % of TLS handshakes (so most chunks need a few tries).

**Search tab, when it breaks** (empty results, "HTTP 400", a channel page showing Home instead of Videos): all
constants are in `Clients.kt` under "Search tab": `WEB_VERSION` (yt-dlp `_base.py` → `'web'` → `clientVersion`),
`SEARCH_VIDEOS/CHANNELS/PLAYLISTS` (the `sp=` value after picking a Type filter on youtube.com/results), and
`TAB_VIDEOS/SHORTS/PLAYLISTS` (only fallbacks: the app reads the real tab params from each channel answer by tab URL;
`TAB_PLAYLISTS` changed in 2026). Parsing (`Browse.kt`) walks the JSON for known renderer keys (`videoRenderer`,
`gridVideoRenderer`, `playlistVideoRenderer`, `compactVideoRenderer`, `reelItemRenderer`, `shortsLockupViewModel`,
`lockupViewModel`, `channelRenderer`, `gridChannelRenderer`, `playlistRenderer`, `gridPlaylistRenderer`, and
`continuationItemRenderer` / `continuationItemViewModel` tokens) and skips headers, menus and engagement panels, so a
moved list usually keeps working; a brand-new renderer name needs one line in `Browse.walk`.

Verified live from a PC on 2026-11-26 with WEB `2.20260708.00.00`, no cookies: search (videos/channels/playlists +
continuation), channel Videos/Shorts/Playlists (+ continuations), the 6,212-video "Uploads from NASA" playlist paged
100 at a time (3 pages, no duplicates), resolve_url for `@NASA`, `/c/NASA`, `/channel/UC…`, a playlist URL and a
missing handle (404), and two RSS feeds (15 entries each; 404 for a missing channel). Findings: playlist pages now use
`lockupViewModel` items with a `continuationItemViewModel` token; the old Playlists tab param opens Home.

**Videos blocked in your country** (e.g. "This content is not available on this country domain due to a legal
complaint from the government."): verified 2026-11-26 from a Turkish IP with channel "Foşix Erlik": YouTube applies
these legal blocks per *country domain*, i.e. the content region in the request (`gl`), not per IP — asking the same
client with `gl=US` returned the video, and googlevideo served every byte range of its streams to the Turkish IP.
Channel pages, search, RSS and listings were not filtered (only playback is). So the app retries a blocked video with
**⋮ → Region for blocked videos** (default US; off shows the block) and remembers such videos so later requests go
straight there. When it still fails: "Blocked in your country and in region US too". If YouTube ever switches to
IP-based enforcement, only a non-Turkish network exit helps (any VPN, or Orbot's VPN mode); the app has no proxy
setting today. (`@FosixErlik` itself resolves to a different, empty channel "Foschix Erlik"; the user's channel is
"Foşix Erlik", `UCXqOIL3_CwYrna1LgYlGL9A`.)

**Channel pages of blocked accounts (1.4).** What the user hit, checked again from the Turkish IP: every API route to
"Foşix Erlik" (`UCXqOIL3_CwYrna1LgYlGL9A`, owner of both blocked videos) works — resolve, Videos / Shorts tabs (it has
no Playlists tab), continuations, RSS (the same 15 entries as from a non-Turkish exit) — and the blocked videos are
listed. The dead ends were elsewhere: (1) the handle `@FosixErlik` belongs to a different channel, "Foschix Erlik",
which is empty everywhere (its RSS is empty from a non-Turkish exit too); (2) YouTube's channel search doesn't list
"Foşix Erlik" at all (in any region); (3) the player and the info card had no way to the video's channel. Fixes: the
player's Channel button, a tappable channel line on the info card, "Visit channel" in every hold menu, and on an empty
channel page the app shows YouTube's message and offers channels with a similar name taken from video results
("Did you mean: Foşix Erlik"). Search / browse / resolve / continuations also get the region fallback: an error, an
alert like "not available / legal complaint", or an empty channel page is asked once more with the fallback region;
a channel that then shows content is remembered and loaded with that region (and its continuations) from then on.

**Player, when it breaks** ("No video stream", Listen failing, wrong default mode): `Clients.kt` →
`PLAY_ORDER` / `HLS_ORDER` (clients whose answers carry `hlsManifestUrl` and full-range streams) and `REMIX_VERSION`
(yt-dlp `'web_music'` clientVersion, only used for the `musicVideoType` hint).

**Downloads, when they break** (typical symptoms: "Sign in to confirm you're not a bot" for every video, "No downloadable
streams", or HTTP 403 on every stream): update **`Clients.kt`** — compare `INNERTUBE_CLIENTS` in yt-dlp's
`yt_dlp/extractor/youtube/_base.py` and `_DEFAULT_CLIENTS` / `_DEFAULT_JSLESS_CLIENTS` in `_video.py`, copy the
client name, id, version, user agent and device fields of clients with `REQUIRE_JS_PLAYER: False` and no required
`GVS_PO_TOKEN_POLICY`, and reorder `Clients.ORDER`. Parsing (`Yt.kt`) rarely needs changes.

## Power and network

- Nothing runs in the background unless a download is in progress. No polling, no timers, no alarms, no restart
  after the process dies (unfinished jobs show "Interrupted" with a Retry button).
- Search tab: network only in response to the user: a search, opening a channel/playlist page or switching its
  sub-tab, Load more, Download all, Refresh, or opening the Feed when its cache is older than 30 min. Every request
  (and queued thumbnail) is cancelled, open sockets disconnected, when the page changes, the tab is left or the app
  goes to the background. Thumbnails load only for rows on screen. Answers come gzip-compressed (a 100-video
  playlist page is ~1.3 MB of JSON before compression). `visitorData` stays in memory only.
- Downloads run in a foreground service (`dataSync`) with a progress notification and a Cancel button. One worker
  thread, one job at a time; the service stops itself as soon as the queue is empty.
- A partial wake lock (and, before Android 14, a high-performance Wi-Fi lock) is held only while a job is
  transferring/muxing.
- **Wi-Fi only** (⋮, default ON): with it on, nothing is fetched over mobile data — not even link info, search,
  feeds or thumbnails.
  Starting a download off Wi-Fi is refused; running downloads have their sockets bound to the Wi-Fi network, so if
  Wi-Fi drops they fail ("Not on Wi-Fi") instead of switching to mobile data, and Retry resumes later.
- Progress is written to memory only; history is saved on state changes. Remux writes straight into the MediaStore
  file (no extra copy).
- Android 15+ limits `dataSync` services to ~6 h a day; `onTimeout` pauses the queue (Retry later).
- Player: no background playback and no service. Leaving the player or sending the app to the background releases
  the MediaPlayer, closes the proxy and stops the filler (partial caches stay and resume next time; you come back
  paused at the same position). The only timer is the seek-bar ticker (500 ms) while the player is visible and
  playing. Streaming honours "Wi-Fi only"; saved and completely cached items always play.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE` (Wi-Fi only), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS` (asked once, at the first download), `WAKE_LOCK` (only during a download). Nothing else — no
storage permission, no account. A `<queries>` entry for `app.localmusic` lets "Show in Music" find LocalMusic.
Unchanged in 1.1–1.3 (the player needs no permission; public cached files are the app's own MediaStore entries; plain HTTP is allowed only to 127.0.0.1): the Search tab needs nothing new (the Takeout file is picked with the system file picker,
`ACTION_OPEN_DOCUMENT`, which grants access to that one file).

## Legal

For personal use only. YouTube's Terms of Service do not allow downloading content except where YouTube itself offers
a download option (e.g. YouTube Premium offline). Only download what you have the right to keep (your own uploads,
Creative Commons, public domain, or where the rights holder allows it). You are responsible for how you use this app.

## Code map

| File | What |
|---|---|
| `Clients.kt` | InnerTube clients + request bodies/headers + Search tab constants — **update this when YouTube breaks it** |
| `Browse.kt` | Search/browse answer parsing (recursive renderer walk, continuations), channel/playlist heads, link classification |
| `Feed.kt` | Follows, Takeout csv/zip import, RSS parsing, feed cache (30-min staleness per channel), bulk plan |
| `Web.kt` | Search-tab network: cancellable InnerTube WEB / RSS calls, thumbnail loader |
| `SearchUi.kt` | Search tab UI: Feed/Search, channel/playlist pages, rows, selection, bulk dialog |
| `Cache.kt` | Range bookkeeping, cache entries + LRU, cache folders, Watch/Listen decision, HLS parsing, ID3 strip |
| `Player.kt` | Caching sources (progressive audio, HLS), the 127.0.0.1 proxy, Save from cache |
| `PlayerActivity.kt` | Player screen (MediaPlayer + SurfaceView) |
| `Yt.kt` | Link parsing, `/player` response parsing, error classification, format choice (`Pick`) |
| `Naming.kt` | Channel/title clean-up, file names, folder layout |
| `Model.kt` | Download jobs + JSON history codec, text helpers |
| `Net.kt` | Wi-Fi binding, InnerTube calls with fallback, chunked resumable `Fetcher` |
| `Mux.kt` | MediaExtractor → MediaMuxer remux/merge |
| `Store.kt` | Settings, job list, MediaStore `Saver` |
| `DownloadService.kt` | App class, foreground service, job runner, notifications |
| `MainActivity.kt`, `Ui.kt` | UI |

`Clients.kt`, `Yt.kt`, `Naming.kt`, `Model.kt` are pure logic, unit-tested in `LogicTest.kt` against `/player`
responses captured on 2026-11-25 (`app/src/test/resources`, stream URLs replaced). `Browse.kt` and `Feed.kt` are
tested in `BrowseTest.kt` against search/browse/resolve answers and an RSS feed captured on 2026-11-26 (tracking
fields, visitorData and IP-bearing URLs stripped; some lists cut to 12 items), plus synthetic old-renderer shapes,
Takeout csv/zip variants, the feed cache and the bulk plan. `PlayerLogicTest.kt` covers ranges, LRU, the cache
folder, the Watch/Listen decision, HLS master/media parsing (captured IOS_PRODUCER manifests), ID3 stripping and the
proxy's HTTP parsing. Tests use `org.json` and `kxml2` (test-only; the app uses
the framework's).
