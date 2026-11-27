# Plain Youtube

(Formerly "Plain Download". Renamed in 1.3; the package stays `app.plain.download`, so updates install over the old
app with its history, follows and caches, and the public folders stay `Music/PlainDownload` and `Movies/PlainDownload`.)

Android app that searches, plays and saves YouTube audio and video from a pasted or shared link for **personal offline use** (hikes,
flights), like the free YouTube-download websites do, but on the phone and without ads or trackers.

- Kotlin, plain Android framework (`HttpURLConnection`, `MediaExtractor`, `MediaMuxer`, `MediaStore`, Views).
  **No AndroidX, no third-party libraries, no ffmpeg/NewPipe.** The only borrowed code is yt-dlp's ejs signature
  solver (JavaScript asset, Unlicense/ISC/MIT), used for members-only videos. The release APK is ~0.5 MB.
- minSdk 30, target 35. Package `app.plain.download` (debug build: `app.plain.download.dev`).
- OLED style shared with LocalMusic: pure black, 1 dp #333 outlines, text #E4E4E4 / #A6A6A6 / #747474, amber accent
  #E6B422. Colours live in `C` (`Ui.kt`).

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

The release APK (`app/build/outputs/apk/release/app-release.apk`, copied to `PlainDownload.apk`) is minified and signed
with the local debug key. `local.properties` must use forward slashes (`sdk.dir=C:/Users/.../Android/Sdk`).

## One library, resume, passive (3.3)

- **Cached = downloaded.** A stream that is played (or "Cache"d) to completion is saved like a download into
  `Music|Movies/PlainDownload/<Channel>/…` and listed with the downloads — there is no `Cached/` folder any more.
  Only unfinished streams stay private ("Partly cached" in the Download tab, still under the cache limits).
- **One copy per video and kind** (`Saved.kt`): `Store` keeps a `videoId:A|V -> content://` index
  (`files/downloaded.txt`, lines `key<TAB>uri`). Download refuses a second copy ("Already downloaded: <path>"), a queued
  job whose video got saved meanwhile finishes pointing at that file, a completed cache of something saved is
  dropped, and the player plays the saved file (offline) instead of streaming. Older "marked but no uri" entries
  adopt their file by name instead of writing "Title (2)". Trashed / deleted files are forgotten and can be
  downloaded again.
- **Migration**, once per Youtube tab start (off the main thread, `CachePublisher.publishAll`): old
  `…/PlainDownload/Cached/…` files move to their channel folder (same file, RELATIVE_PATH update) or, when they
  duplicate a download, are deleted; stale `.pending-…` entries of a killed save are deleted. It no longer runs at
  every app start, so the Music tab alone never touches it.
- **Continue from last session?** (`Resume.kt`, prefs `resume`): the position of each video is kept when the player
  pauses, closes or moves on (no timer); opening it again asks Continue / From the beginning. Kept only between
  15 s from the start and 15 s before the end; played to the end = forgotten. Queue auto-advance doesn't ask.
- **Passive**: nothing on the network unless the user taps something (feeds, Spotify sync, downloads, playback).

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
- Clear errors for age-restricted, private and live videos. Members-only videos play and download only with the
  optional account (⋮ → Account, 2.0; see below); everything else is anonymous.

## Playback survives the app being swiped away (2.5)

**One player per process.** `PlayerPanel` is a singleton created with the **application context**
(`PlayerPanel.get`), so nothing in it refers to an Activity.
- **Kept alive while playing by `PlayService`.** The player owns everything that plays: MediaPlayer, caching proxy,
  queue, audio focus, the background audio/video switch, MediaSession and notification.
- **Shown by whichever MainActivity is alive.** On create, MainActivity calls `attach(this)` and adds the player's
  views (built once with the application context) to its window. On destroy it calls `detach(this)`, which removes
  them from the old window, drops the surface (`setDisplay(null)`) and forgets the activity. Dialogs opened from the
  player (speed, quality, queue, Save) use the attached activity; without one they simply don't open.
- **Honest note:** this is one class with a strict attach/detach boundary rather than two classes. The views are kept
  and re-hosted instead of being rebuilt.

**Rules** (`EngineRule`, unit-tested):
- **Destroyed while playing** (swipe from recents, or the system): the player goes on audio-only under the
  foreground service, exactly like the background case.
  - Watch streams switch to the audio stream after 5 s.
  - The item, position, mode and queue flag are saved (`lastPlay`).
- **Destroyed while paused:** everything closes, as a swipe always did.
- **Reopened** (launcher or notification): the new MainActivity reattaches. It shows the mini window, or full if it
  was full, in the current state, and Watch switches back to the video stream at the current position.
- **Paused with no window:** player, proxy and read-ahead are released; the saved state is enough to resume.
- **A notification button or headset press when the process was gone** rebuilds the player from the saved state and
  acts. Play starts the service first, then the source; a Watch item plays audio-only.
- **The next app open after process death** shows the saved item paused at its position (mini window). Close or
  swiping the paused notification away clears the saved state.

## Background audio (2.4)

**Watch and Listen both keep playing audio** when the app goes to the background or the screen turns off.

**Service lifecycle** (`PlayService.kt`, the pattern of LocalMusic's PlaybackService).
- **What it is.** A `mediaPlayback` foreground service with no work of its own; the player stays in MainActivity's
  `PlayerPanel`.
- **Foreground** while the player wants to play, including buffering, the next queue item and the audio/video
  switch. It starts when playback starts, while the app is visible.
- **On pause:** `stopForeground(DETACH)` + `stopSelf()` at once. The paused notification stays, can be swiped away,
  and its buttons still work. A pause while in the background also releases the player, proxy and read-ahead, so
  nothing runs. Play resumes from the same place: the service is started first, inside the notification tap's
  permission window, then the source.
- **Close** (notification, ✕, or MediaSession stop) stops and removes everything.
- **The notification** shows the thumbnail (low-res, Wi-Fi only respected), title and channel, with ⏮ −10 s ▶/❚❚
  +10 s ⏭ (queue only) and Close. A framework `MediaSession` drives the lock screen, quick settings and
  Bluetooth/headset buttons.
- **Wake lock:** `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)`, held by MediaPlayer only while it plays.
- **The caching proxy and read-ahead** keep running while playing and stop with playback.
- **Swiping the app out of recents** closes a paused player. A playing one goes on until Close, even when the activity
  is destroyed (2.5).

**How Watch drops the video in the background** (`BgVideo`, unit-tested).
- **Saved files and complete caches:** the surface is detached (`setDisplay(null)`), so the same MediaPlayer plays
  audio only and the video decoder stops.
- **Streamed video** (HLS, or a members-only MP4):
  - The surface is detached at once.
  - After **5 s** still in the background, the player switches to the audio-only stream at the same position (itag
    140/251, the Listen path, cached under the Music cache rules), so no more video segments are fetched.
  - Back in the app, it switches back to the video stream at the current position, with the surface attached.
  - The gap is kept short: the new source is resolved (cache, YouTube's answer) while the old one keeps playing.
    Only then is the old player released and the new one prepared.
- **PiP:** video stays while visible. Screen off in PiP is handled like the background (audio only after 5 s);
  screen on brings the video back.
- **Queue:** auto-advances in the background. Listen items play as audio; Watch items start audio-only and switch
  to video when the app is shown again.

**Plain Music and other apps.**
- **Audio focus:** `AUDIOFOCUS_GAIN` on play. Loss → pause (so starting Plain Music pauses Plain Youtube; Plain
  Music also takes and handles focus, so it works the other way round).
- **Transient loss** (a call) → pause, then resume on gain.
- **Can-duck** → volume 25 %.
- **Unplugged headphones / Bluetooth gone** (`ACTION_AUDIO_BECOMING_NOISY`) → pause. That receiver is registered only
  while playing.

**Battery.**
- Paused or closed: no service, no wake lock, no proxy, no read-ahead, no receivers except the media session.
- In the background: no video decoding and no video segment downloads after the 5 s switch.
- The only new timer is that 5 s delay (once per trip to the background).

**Permissions:** `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is new (needed for the mediaPlayback service). Nothing else
changed.

## Player overlay, mini window, picture-in-picture, Back history (2.3)

**One player for the whole app.** `PlayerPanel` (`PlayerPanel.kt`) owns the MediaPlayer, the caching proxy and the
queue, and lives as an overlay inside MainActivity (the separate PlayerActivity is gone; `PlayerActivity.stream/queue/
local` still exist as entry points and route into the overlay). States (`PlayerUi`, unit-tested):

- **Full**: the player screen.
  - The ⌄ button, Back, a swipe down, or opening anything from the player (Channel, Download…, the channel line)
    **minimises** it. Navigating never stops playback.
  - Tap shows or hides the controls; while a video plays they hide after 3 s (one delayed post, only while the
    player is full).
  - Double-tap the left or right half to seek ∓10 s, with a short "« 10 s" mark.
  - The seek bar is bigger; its grey part is what's cached.
  - Speed 0.5–2× uses `PlaybackParams`. A paused player gets the new speed when it next plays, because setting it
    would start playback.
  - "Rotate: auto/locked" and Full screen.
  - Title, plus a channel line that opens the channel.
  - "Up next" peek of the play queue.
- **Mini**: a 16:9 video window (40 % of the width, 30 % in landscape), outlined, pinned to a corner of the app above
  every tab and page.
  - Tap → full. ▶/❚❚, ✕ (stop) and the PiP button sit in its corners.
  - Drag it to any corner; top-right by default, and the corner is remembered.
  - Listen shows a compact card instead: cover, title, ▶, ✕.
- **PiP**: the activity in picture-in-picture with only the video.
  - Actions: −10 s, play/pause, +10 s (`RemoteAction`s → a broadcast receiver that isn't exported).
  - Expanding PiP returns to the app exactly as it was (mini or full, same page).
  - Closing the PiP window stops the player.
  - **Where the window goes is Android's decision.** It usually starts bottom-right and the user can drag it. The
    app only passes the aspect ratio and a `sourceRectHint`; the window animates out of the top-right mini window,
    which is what the app minimises to first.
  - Auto-enter on Home is a ⋮ setting, off by default: `setAutoEnterEnabled` on Android 12+, `onUserLeaveHint` on 11.
  - Manifest: `supportsPictureInPicture` and `resizeableActivity` on MainActivity, whose `configChanges` already
    covered size and orientation. No new permission.
- **Closed.**

**Volume.** On the full player there's a speaker icon and a horizontal bar for the media stream
(`STREAM_MUSIC` via `setStreamVolume(…, 0)`, so no system panel appears).
- The speaker icon mutes; tapping it again brings back the remembered level.
- The bar follows the hardware keys through a `VOLUME_CHANGED_ACTION` receiver. That receiver is registered only
  while the full player is shown and the app is in front.
- `setVolumeControlStream(STREAM_MUSIC)`: the volume keys change media volume anywhere in the app.

**Battery (2.3; changed in 2.4, see "Background audio"):** playback ran only while something of it was visible.

**Back = one step back** (`NavHistory` + `BackRule`, unit-tested).
- Dialogs and menus close first by themselves.
- Then, in this order: end a selection, leave the player's full screen, minimise the full player, pop the history.
- The history records tab switches, Feed/Search/Lists switches, opened pages (channel → playlist → … and Following),
  and the Cached folder. Pages keep their scroll position because the page objects stay alive.
- At the start, Back shows "Press back again to exit"; a second Back within 2 s moves the app to the background.
- The mini player stays while you go back.
- `OnBackInvokedCallback` (API 33+) and `onBackPressed` (API 30–32 and as a fallback) share one handler; a second
  call for the same press is ignored. Predictive back stays off.

## Filters, Following selection, members-only in Search and Feed (2.2)

**Search filters.** Under the search box there's a "Filters" row of chips (amber = active, **Reset** clears them).
They are remembered.

| Filter | Options | Where it's done |
|---|---|---|
| Type | Videos / Channels / Playlists | YouTube (`sp`) |
| Upload date (videos) | Any / Today / This week / This month / This year | YouTube (`sp`) |
| Duration (videos) | Any / Under 4 min / 4–20 min / Over 20 min | YouTube (`sp`) |
| Sort | Relevance / View count | YouTube (`sp`) |
| Sort: Upload date | newest first | **here**: YouTube ignores its date sort now, so the loaded results are sorted by their "3 days ago" text |
| Features | Hide Shorts, Hide live, Members-only only, Music only ("- Topic" channels), Hide downloaded | **here**, over the loaded pages |

- **How `sp` is built.** `sp` is YouTube's protobuf `SearchParams {1: sort, 2: {1: date, 2: type, 3: duration}}`,
  built by `SearchParams`. All 44 combinations the app can build (videos 5×4×2, channels and playlists ×2 sorts) were
  sent to YouTube on 2026-11-26. Every one returned results, with durations inside the asked range. That list is the
  test fixture `sp_expected.txt`.
- **What YouTube ignores.** Sort 2 (upload date) gave the same answer as relevance, so it isn't sent. Date and
  duration are dropped for channels and playlists.
- **Client filters scan further.** With a client-side filter on, the app keeps loading pages until 10 new rows pass or
  5 pages were scanned. Load more continues the same way.
- **"Music only" is cheap on purpose.** It only checks for "- Topic" channels. Checking each video for YouTube
  Music's ATV type would cost one request per video.
- **Shorts detection.** Search Shorts are recognised by their Shorts-player link, RSS Shorts by their `/shorts/` link.

**Members-only in Search: YouTube leaves them out.** Checked live: anonymous search never returns members-only videos,
even for their exact titles, and no members badge appeared in any search answer. Search stays anonymous, so "Members-only
only" there is usually empty and says so. The badge code does render them if YouTube ever includes them
(`metadataBadgeRenderer` / `badgeViewModel`). A channel's **Members** list shows them.

**Feed filters** (all local): Members only, Hide Shorts, Hide downloaded, Music only, channel picker (one or several
followed channels; none = all), age (24 h / week / month / any), sort (newest / by channel). They are remembered.

**Members-only in the Feed.**
- **Channel RSS leaves them out.** Checked live with hololive channels: none of the badge-marked members-only videos
  were in `feeds/videos.xml?channel_id=…`.
- **A public feed lists them.** `feeds/videos.xml?playlist_id=UUMO…` lists a channel's 15 newest members-only uploads,
  **anonymously**. It answers 404 for channels without memberships.
- **So no account is used here.** On a manual **Refresh** the app also reads that feed for each followed channel. Those
  uploads get the MEMBERS badge and are added to the feed even though they're missing from the channel RSS. A 404 skips
  that channel for a week. Automatic refreshes keep the members-only uploads found earlier.
- **Signed out:** the badge still shows; tapping one gives the sign-in hint.

**Following list.**
- **Filter box:** matches the name; accents and case are ignored.
- **Select:** a header button, or hold a channel → Select. The selection bar shows "N selected", All and **Unfollow N**.
  Back or ✕ ends selection.
- **One confirmation:** the count, up to 5 names and "+K more". When signed in it adds "Also unsubscribe on YouTube"
  (remembered) and the paid-membership warning for known memberships.
- **Unfollow:** the local unfollow happens at once and shows an 8 s **Undo** bar. Undo restores the follows only; the
  bar says "YouTube unsubscribes can't be undone here" when that option was used. Unsubscribes then run one after
  another, 0.8 s apart: "Unsubscribed N of M", Retry for failures.

## Account for members-only videos (2.0)

Plain Youtube stays anonymous. An account can be added for **one thing only**: members-only videos of channels you're
a member of. It also powers a manual **Sync subscriptions**. Everything else (search, feeds/RSS, channels, playlists,
music pages, every other video and all thumbnails) is still asked with no account and no cookies.

**What you see**
- Members-only videos have a **blue MEMBERS badge** (#4A90E2) on list rows, in the player's top bar, on the info card
  and in the downloads list. YouTube marks them in listings (`metadataBadgeRenderer.style =
  BADGE_STYLE_TYPE_MEMBERS_ONLY`, `badgeViewModel.badgeStyle = BADGE_MEMBERS_ONLY`, "Members only"/"Members first"),
  and the player answer says "Join this channel to get access to members-only content…".
- Signed out: tapping one shows "Members-only — import your account in ⋮ → Account".
- Signed in: Watch, Listen, Cache and Download work as for any video. On a channel page a blue **Members** button
  opens the channel's members-only list (its `UUMO…` playlist, asked with the account).
- ⋮ → **Account…**: Sync subscriptions, Test the account, Sign in to YouTube (on this phone), Import cookies.txt…,
  How to get cookies.txt…, Sign out, and what the account is used for.

**Three ways to add the account**
- **A. Sign in on the phone, inside the app.** ⋮ → Account → Sign in to YouTube opens Google's sign-in page in
  Android's WebView **exactly as it comes**: the user agent isn't changed, `X-Requested-With` isn't removed, nothing
  hides the WebView. Only JavaScript and DOM storage are switched on (the page can't work without them).
  - **Usually refused.** Google tends to refuse embedded browsers with "This browser or app may not be secure" /
    "Couldn't sign you in". That's a security control and the app doesn't get around it. It spots the refusal by the
    page address (`/signin/rejected`, `disallowed_useragent`, …) and by the page text (one read-only `innerText`
    check), wipes the WebView and explains B and C.
  - **Other apps disguise the WebView.** Apps that make WebView sign-in "work" (e.g. YTDLnis sets a desktop Chrome user
    agent in its WebView sign-in screen) do so that way. Plain Youtube doesn't.
  - **If Google accepts it.** Once back on youtube.com with `LOGIN_INFO` + `SAPISID`, the app reads the youtube.com
    cookies with `CookieManager.getCookie`. That includes HttpOnly cookies; if a WebView doesn't hand over
    `LOGIN_INFO`, the app says so and offers B/C. It keeps the sign-in ones, then clears **all** WebView cookies, DOM
    storage, cache, history and form data.
- **B. Phone only, with Firefox for Android.** Install Firefox and add the **"cookies.txt"** add-on by Lennon Hill
  (<https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/>). It works on Firefox for Android 121+, is GPL-3.0,
  says "no data collected", and exports Netscape cookies.txt. Then:
  1. Open a **private tab** and sign in on youtube.com.
  2. In the add-on, export the current site. It saves `cookies.txt` to Downloads.
  3. **Close the private tab.** Otherwise YouTube keeps rotating that session's cookies and the exported ones expire
     sooner.
  4. ⋮ → Account → Import cookies.txt…, then delete the file. The app offers to delete it if the file's provider
     allows it.
- **C. PC.**
  1. Sign in on youtube.com in a private/incognito window.
  2. Export with a local-only extension, e.g. "Get cookies.txt LOCALLY" (Chrome) or "cookies.txt" (Firefox). Export
     youtube.com only.
  3. Close the private window.
  4. Copy the file to the phone by cable, import it, and delete it on both devices.

**Security**
- **What's kept.** Only YouTube's sign-in cookies on `youtube.com`: `SID HSID SSID APISID SAPISID LOGIN_INFO SIDCC
  __Secure-1PSID __Secure-3PSID __Secure-1PAPISID __Secure-3PAPISID __Secure-1PSIDTS __Secure-3PSIDTS
  __Secure-1PSIDCC __Secure-3PSIDCC`.
- **What's dropped.** Every other line of a cookies.txt: other sites, `google.com` (never sent anywhere, so not
  worth keeping), and tracking or preference cookies (`VISITOR_INFO1_LIVE`, `PREF`, `YSC`, …). The import only
  reports a count of dropped lines.
- **Encryption.** The cookies are stored AES-256-GCM encrypted in `files/account.bin`, with a non-exportable Android
  Keystore key (`plain_youtube_account`, hardware-backed where the phone has it).
- **No copies leave the phone.** `allowBackup=false`, `fullBackupContent=false`, and `dataExtractionRules` excludes
  everything from cloud backup and device transfer. A copied file would be useless anyway, because the key never
  leaves the phone.
- **Sign out** deletes the file, the key, the in-memory copy and any WebView cookies/storage.
- **Never logged.** Cookie values never go to logcat, history, notifications, error text or test fixtures. Even
  `Cookie.toString()` hides the value. The unit tests use made-up values only.
- **Rotation and expiry.** `Set-Cookie` headers on authenticated answers from youtube.com are applied to the stored
  jar (YouTube rotates `__Secure-*PSIDTS` / `SIDCC`) and re-encrypted. A `401`, a `loggedOut: true` answer or an
  account check without a name marks the account **expired**: "Account expired — import new cookies".

**Separation guarantee.** Two layers keep the account off ordinary requests:
- **One decision function.** `AuthRule.useAccount(purpose, url, signedIn, membersOnly)` (`Account.kt`, unit-tested)
  allows the account for exactly four purposes:
  - `ACCOUNT_CHECK` (Test, and the name after sign-in or import);
  - `SUBSCRIPTIONS_SYNC`;
  - `MEMBERS_PLAYER`, only for a video YouTube called members-only;
  - `MEMBERS_TAB`, only for the Members list you opened.

  It also requires https on `www.youtube.com` / `youtube.com` / `music.youtube.com` / `m.youtube.com`.
- **One sender.** `AuthHttp` is the only code that sets a `Cookie` or `Authorization` header (`grep` confirms it). It
  refuses before sending if the rule says no, and doesn't follow redirects, so a cookie can't travel to another host.
  Every other request (`InnerTube.anon`, `WebApi`, `Fetcher`, thumbnails, RSS) uses code that has no access to the
  jar. No global `CookieHandler` is installed.
- **googlevideo.com gets no cookies.** The signed stream URL is its own authorization.
- **The player JS is public.** `iframe_api` and the player JS are fetched without the account.

**Members-only playback path** (`Auth.kt` → `AuthPlayer`), used only for members-only videos:
1. `GET https://www.youtube.com/iframe_api` (anonymous) gives the player id. `tv-player-ias.js` (~2.5 MB) is
   downloaded once per player version into the app cache, and its `signatureTimestamp` is read.
2. Authenticated `POST /youtubei/v1/player` uses the **TVHTML5** client: yt-dlp's `tv` / `tv_samsung`, version
   `5.20260707`, which supports cookies and has no PO-token policy for streams. The headers are `Cookie`,
   `Authorization: SAPISIDHASH … SAPISID1PHASH … SAPISID3PHASH …` (sha1 of `"<ts> <SAPISID> https://www.youtube.com"`,
   as yt-dlp builds it), `X-Origin` and `X-Goog-AuthUser: 0`. The body carries
   `playbackContext.contentPlaybackContext.signatureTimestamp`, and a bot check gets one retry with `visitorData`.
3. The stream URLs' **`n`** parameter (and **`s`** of `signatureCipher`) are solved by **yt-dlp's ejs solver**
   (`assets/ejs/yt.solver.lib.min.js` + `yt.solver.core.min.js`, ejs 0.8.0, SHA3-512 checked against yt-dlp's
   `_vendor/_info.py`). The solver runs in an **off-screen WebView** with no network (`blockNetworkLoads`), no
   file/content access and no page. It is destroyed right after, and only loaded for members-only videos.
   **Licences:** ejs is Unlicense (public domain); it bundles **meriyah** (ISC) and **astring** (MIT). Their licence
   texts stay in the file headers.
4. The rewritten answer goes through the normal parser. Formats whose `n` wasn't solved are dropped, because they
   would be throttled or refused.
5. Download and Listen use the adaptive streams as usual. The TV client gives no HLS, so in-app **Watch** of a
   members-only video plays and caches the muxed 360p MP4 (itag 18) as one file in the Video cache. **Download** gets
   any height, merged as usual.
6. A 403 on the streams asks again once. Videos YouTube called members-only are remembered (`members` in settings),
   so their `/player` goes straight to this path.

**Depends on:**
- the TV client version in `TvPlayer` (`Account.kt`);
- yt-dlp's ejs solver handling the current player (update the two asset files from a new ejs release when YouTube
  changes the player);
- the player-id regex on `iframe_api`.

The end-to-end chain (iframe_api → TV player JS → sts → TV `/player` → solver → 206 byte ranges) was verified from a PC
**anonymously** on 2026-11-26 with node. The exact script the app builds also ran in a clean JS context (1 s, both `n`
challenges solved). **Not verified:** an authenticated request with real cookies (none on the PC; on purpose), the
solver inside an Android WebView on the phone, and WebView sign-in on the phone.

**Sync subscriptions** (manual only): an authenticated `browse` of `FEchannels` plus continuations (at most 40
pages). The channels (`channelRenderer`, also channel lockups) are merged into Follows. The result reads
"Added N, already followed M". Followed channels that aren't subscribed on YouTube are listed, and the app asks:
**Keep** (default) or Unfollow. The feed itself stays anonymous RSS. A membership badge in the Following list was
skipped: the subscriptions answer doesn't say which channels you're a member of.

**Unfollow → unsubscribe on YouTube (2.1).** When signed in, unfollowing a channel (channel page button, or hold →
Unfollow in Following/search) asks once:
- **The confirmation** has a checkbox **"Also unsubscribe on YouTube"**. It remembers your last choice and starts on.
- **The local unfollow** happens at once. The unsubscribe is then one authenticated request on a plain thread of
  that action (no service).
- **The endpoint** is `POST /youtubei/v1/subscription/unsubscribe` with the WEB context and `channelIds: ["UC…"]`,
  the web client's `unsubscribeEndpoint`. Its `params` is only UI-source context, and YouTube Music's own endpoint
  omits it, so none is sent.
- **The rule:** purpose `UNSUBSCRIBE` in `AuthRule`, https `youtube.com` / `www.youtube.com` only, sent only through
  `AuthHttp`.
- **The result:** a toast, "Unsubscribed on YouTube", or "Couldn't unsubscribe on YouTube: <reason>" plus a small
  dialog with **Retry** (a toast can't hold a button).
- **Several channels:** one confirmation, then requests one after another with 0.8 s between them, ending with
  "Unsubscribed N of M".
- **Paid memberships:** if the account could play a channel's members-only videos (so it's a member), the
  confirmation warns that unsubscribing does **not** cancel the paid membership; that's managed on YouTube.
- **Signed out or expired:** no checkbox, local unfollow only, as before. Nothing is ever unsubscribed without that
  confirmation.
- **The sync's "not subscribed on YouTube" list** only unfollows locally, because those channels aren't subscribed
  there.

Not verified live: there's no account on the PC, so no real unsubscribe was sent.

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
  so it shows at once, also offline. The Feed never fetches by itself (2.11, user's rule: nothing from the
  internet unless asked): only **Refresh** fetches, all followed channels; new follows show "tap Refresh". At most 4 RSS requests run at once. **Following (N)** lists the
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
- **Audio language (2.8).** Dubbed videos (YouTube auto-dubs) carry one audio track per language. Every client
  lists them alphabetically with `DEFAULT=NO` everywhere, the uploader's track last (HLS `NAME="English - original"`,
  stream URL xtags `acont=original`; `/player` `audioTrack.displayName` "English original", id `en.4`), so taking the
  first one played **Arabic** (verified on KrLj6nc516A, 25 dubs). The player, Listen, Download and Cache jobs now take
  the original (`AudioTracks.select`, `Hls.pickRendition`); an **Audio** button next to Quality (only for dubbed
  videos) picks another language, remembered per video (`audio_langs` prefs; the original is not stored). Cache
  entries of a dubbed video carry the language in the key (`<id>_<q>_<lang>`); single-track keys are unchanged.
  Entries cached before 2.8 (`lk` missing) may hold a dub: the Audio button shows for them and any pick leaves them.
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
- **Complete = saved** (3.3; 1.3–3.2 used a temporary `…/PlainDownload/Cached/` tree): when a cached entry is
  complete it is remuxed / joined (no re-encoding, same code as Save) through MediaStore into the normal download
  folder, `Music/PlainDownload/<Channel>/[<Album>/]<Title>.m4a` (or `.ogg`) or `Movies/PlainDownload/<Channel>/<Title>.mp4`,
  added to the downloads list, and the private copy and the cache entry are deleted (see "One library" above). The
  rest of this paragraph describes the pre-3.3 behaviour: This happens when the player closes (the player may still be reading the private
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
| Fully cached audio / video | as saved (3.3) | the same folders as saved files (before 3.3: `…/PlainDownload/Cached/…`) |

- Channel = uploader name with `" - Topic"` stripped (YouTube Music auto channels). A channel named exactly
  "Cached" (any case) becomes "Cached (channel)" so it can't clash with the cache root.
- The album level exists only when an album is known (see Albums above); video never has one.
- Names are made safe (`/ \ : * ? " < > |`, control characters, leading dots) and cut to 120 UTF-8 bytes.
- Never overwrites: `Title (2).m4a`, `Title (3).m4a`… for a *different* video with the same title (MediaStore also
  renames on a clash with other apps' files). The same video is never saved twice (3.3).
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
JS player; `tv_samsung` answered "The page needs to be reloaded" in 1.0), `WEB*` (PO token). 2.0 uses `TVHTML5` with
the JS player only for members-only videos with the account (see "Account for members-only videos").

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

**Compact lists, page bars, share (2.12).** The page bar is above and below the rows (2.11's Feed never showed it:
pages overriding `onShow` skipped the footer setup). Rows are tighter (112 dp thumbnails, 13 sp titles, channel ·
views · age on one line, thinner selection outline); Feed actions are small pills and its filters one sideways-
scrolling line. "Share link…" in every video hold menu and a Share button in the player send `https://youtu.be/<id>`
through Android's share sheet (the app sends nothing itself).

**Page-based lists (2.11).** Every list (Feed, Search, Following, channel tabs, playlists, local lists) shows 20 rows
per page with ‹ Previous · Page X of Y(+) · Next › at the bottom instead of a growing list with "Load more". Loaded
rows stay in memory (selection and "All" still cover everything loaded); Next past the loaded rows asks YouTube for
the next batch and the page fills in. "+" = YouTube has more. A new search, tab or filter goes back to page 1.

**Blocked channels in Search and Feed (2.10).** Re-checked 2026-11-26 from the Turkish IP: the channel page of
"Foşix Erlik" now says "This channel is not available." without `gl` (the region fallback opens it), and channel search
still doesn't list it in any region (`gl` none/TR/US) — only its @handle finds it — while video search lists its videos
with it as owner. So a **Channels** search also runs a video search for the same words and adds owners whose name
matches the query and who are missing from the channel results (at most 3, marked "Missing from YouTube's channel
search · found by its videos"; `Browse.missingChannels`). Its RSS still answers normally (15 entries). The Feed's info line
now names followed channels that were fetched but gave no videos (`FeedCache.emptyChannels`), which catches following
the empty look-alike `@FosixErlik` ("Foschix Erlik") instead of the real channel.

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
  sub-tab, Load more, Download all, Refresh. Every request
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
storage permission. A `<queries>` entry for `app.localmusic` lets "Show in Music" find LocalMusic.
Unchanged in 1.1–1.3 (the player needs no permission; public cached files are the app's own MediaStore entries; plain HTTP is allowed only to 127.0.0.1): the Search tab needs nothing new (the Takeout file is picked with the system file picker,
`ACTION_OPEN_DOCUMENT`, which grants access to that one file).
Unchanged in 2.0: the account needs no permission (no `GET_ACCOUNTS`, no `USE_CREDENTIALS`; cookies.txt is picked with
`ACTION_OPEN_DOCUMENT`, the sign-in page and the solver are WebViews inside the app).

## Legal

For personal use only. YouTube's Terms of Service do not allow downloading content except where YouTube itself offers
a download option (e.g. YouTube Premium offline). Only download what you have the right to keep (your own uploads,
Creative Commons, public domain, or where the rights holder allows it). You are responsible for how you use this app.

## Code map

| File | What |
|---|---|
| `Account.kt` | 2.0 pure logic: cookies.txt parsing, cookie jar + Set-Cookie, SAPISIDHASH, `AuthRule` (the separation rule), members-only detection, TV player challenges/rewrite, subscriptions parsing |
| `Auth.kt` | 2.0 Android side: Keystore-encrypted `Account` store, `AuthHttp` (the only cookie sender), `AuthPlayer`, `JsSolver` (off-screen WebView) |
| `AccountUi.kt`, `SignInActivity.kt`, `UnfollowUi.kt` | ⋮ → Account, cookies.txt import, in-app sign-in (unmodified WebView), subscriptions sync |
| `Clients.kt` | InnerTube clients + request bodies/headers + Search tab constants — **update this when YouTube breaks it** |
| `Browse.kt` | Search/browse answer parsing (recursive renderer walk, continuations), channel/playlist heads, link classification |
| `Filters.kt`, `Select.kt` | 2.2 search `sp` builder, Search/Feed filters, selection model, unfollow texts |
| `Feed.kt` | Follows, Takeout csv/zip import, RSS parsing, feed cache (30-min staleness per channel), bulk plan |
| `Web.kt` | Search-tab network: cancellable InnerTube WEB / RSS calls, thumbnail loader |
| `SearchUi.kt` | Search tab UI: Feed/Search, channel/playlist pages, rows, selection, bulk dialog |
| `Cache.kt` | Range bookkeeping, cache entries + LRU, cache folders, Watch/Listen decision, HLS parsing, ID3 strip |
| `Player.kt` | Caching sources (progressive audio, HLS), the 127.0.0.1 proxy, Save from cache |
| `PlayerPanel.kt`, `PlayerActivity.kt` | The app-level player overlay (full / mini / PiP; MediaPlayer + SurfaceView) and its entry points |
| `PlayService.kt` | 2.4 mediaPlayback foreground service + media notification buttons (only while playing) |
| `Nav.kt` | 2.3 Back history, Back order, player overlay states, mini corners, volume/mute model |
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

## Spotify playlists (3.2; no sign-in since 3.4)

Search tab → **Spotify** chip. Spotify's audio is never used; only public playlist lists are read, and every song is
played from YouTube. There is no Spotify account in the app any more (3.4 deletes the old `spotify_account.bin` and
its Keystore key once).

- **Find** (`SpotifySync.find`): type a profile link (Profile → ⋯ → Share → Copy link), a `spotify:user:` uri, a
  username, or a playlist link. A profile is read from its public pages by `SpotifyWeb`: an unmodified-except-UA
  WebView in a small "Reading Spotify…" card (the page must be on screen to render its lists), ~1000 CSS px wide and
  reporting a desktop Chrome user agent with the WebView's own Chrome version — the phone layout shows only 20
  playlists / 30 songs (the user chose the desktop page on 2026-11-27, for these public pages only). Our script
  (`PROFILE_JS`) collects `/playlist/` links (footer and nav excluded), then the profile's "Show all" page. Cookies
  and storage are wiped after each read. The user ticks which playlists to convert (Convert & sync).
  A display name isn't a username: names that don't resolve say so and point to the profile link.
- **Sync** (only on Find's Convert & sync / Sync / "Try the not-found songs again"; one background thread):
  1. each chosen playlist's embed page (`open.spotify.com/embed/playlist/<id>`, plain HTTP, Wi-Fi only applies):
     its `__NEXT_DATA__` lists up to 100 tracks (artists joined by ",\u00A0"); a playlist with 100 is read from its
     full public page by `SpotifyWeb` (`PLAYLIST_JS`: scrolls row by row with `scrollIntoView`, since rows exist only
     near the view, until the "N songs" total). Snapshot = hash of the track ids. Unreadable (private / removed)
     playlists keep their last synced songs. Pre-3.4 "Liked Songs" is private: kept as last synced.
  2. Matching (3 at a time): YouTube Music search (WEB_REMIX `search`, Songs filter, then Videos) for
     "artist title", scored by `SpMatcher`: title words (feat./remaster notes removed), **artist must match**,
     length within 20 s, live/remix/slowed/cover versions penalised unless Spotify's title says so.
     Matches are kept per Spotify track id, so a song in several playlists is searched once.
  Everything is in `files/spotify.json` (`profile`, `user`, playlists, tracks, matches; saved as it goes).
- **Pages:** the Spotify page lists the playlists ("42 of 45 found · 3 not found"); hold for Listen all /
  Download all / Cache all / Copy to my lists. A playlist page shows the found songs under Spotify's title and artist
  (so downloads go to `Music/PlainDownload/<Artist>/<Playlist>/`); tap a song to listen from there. Hold a song →
  "Wrong song? Find another version" (that video is never picked again) or "Search YouTube for it".
  **Not found (N)** lists the songs YouTube Music didn't have; tap one to search YouTube.
- **⋮**: Choose playlists of <profile>… (Find again), Try the not-found songs again, Forget the Spotify playlists
  (downloaded songs stay). Hold a playlist → Remove from here.
