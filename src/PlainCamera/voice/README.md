# Record Voice (tab of Plain Camera)

Simple audio-only recorder with its own list of recordings. Module `:voice`, namespace `app.plain.voice`,
tab key `voice` ("Record Voice" / "Ses Kaydı"). Resources carry the `voc_` prefix.

## Files

| File | What |
|---|---|
| `VoiceActivity.kt` | The tab: Record / Stop + Pause, clock, page-based list, inline player, share / rename / delete |
| `RecorderService.kt` | Microphone foreground service that owns the `MediaRecorder` while recording; state in its companion |
| `Recordings.kt` | Media-store access: list own recordings, create the pending entry, publish, rename, delete |
| `Format.kt` | Pure helpers (unit-tested): file name, folder per SDK, clock / duration / size, paging, name cleaning |
| `Ui.kt` | OLED palette and small view helpers |

## Recording

- `MediaRecorder`: MIC → AAC in MPEG-4 (`.m4a`, `audio/mp4`), mono, 44.1 kHz, 96 kbps. Pause / Resume use
  `MediaRecorder.pause()/resume()`.
- Written through MediaStore Audio into a pending (`IS_PENDING=1`) entry named `Voice 2026-11-27 14-03-22.m4a`,
  published when stopped; removed when recording fails or is too short.
- Folder (`RELATIVE_PATH`): `Recordings/Plain Voice/` on Android 12+ (`DIRECTORY_RECORDINGS`, API 31);
  `Music/Plain Voice/` on Android 10–11, where Recordings/ isn't an allowed audio folder. The insert asks for
  `IS_MUSIC=0` (and `IS_RECORDING=1` on 12+); the media store may recompute those from the folder.
- Recording runs in `RecorderService` (`foregroundServiceType="microphone"`), started by Record and stopped by
  itself when the file is saved, so it continues with the screen off or in another tab. Its low-importance ongoing
  notification shows "Recording…" with the system's own chronometer and a Stop button.
- A phone or internet call (Android 12+: the audio mode leaves "normal", ringing included) pauses the recording, which
  goes on by itself when the call is over; a pause made by the user stays. The listener exists only while recording.
- Near-full storage: the recorder gets a size limit of the free space less 16 MB, so it stops and saves instead of
  breaking off unsaved; with less than that free, Record says the storage is full.
- A recording cut off by the app being killed stays a pending (hidden, unplayable) entry; opening the tab while
  nothing records removes such entries older than 5 minutes.

## List

- The app's own files under either folder, newest first, fixed pages (Previous / Next). No storage permission:
  an app sees its own media-store entries. Recordings left by an earlier install don't show.
- Tap a row: play / pause in place (`MediaPlayer`, seek bar in the row, transient audio focus, pauses when
  headphones are pulled). Playback is released in `onStop`.
- Hold or ⋮: Share (`ACTION_SEND`, `audio/mp4`, read grant, chooser), Rename (`DISPLAY_NAME`; a name another
  recording has gets " (2)"; at most 120 characters / 200 bytes), Delete (confirmed,
  plain delete; only if Android says the file isn't ours does it fall back to a trash request on 11+, or the
  system's permission prompt on 10).

## Battery

Nothing runs while the tab isn't open, except an active recording. The on-screen clock and seek bar tick only
while the Activity is resumed.

## Permissions

`RECORD_AUDIO` (asked at the first Record tap, with an explanation and an app-settings button if denied),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (Android 13+, asked once with the
first recording). No network, no storage permission.
