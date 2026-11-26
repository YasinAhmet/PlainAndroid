# Trekmap Trail (Android)

The mobile companion to desktop Trekmap. It is built for a Tecno 20 Pro (HiOS, Android 13/14), but runs on any phone with Android 8 or newer.

The app has two tabs and no other screens.

| Tab | What it does |
|---|---|
| **Plan** | Browse the map (online on Wi-Fi, otherwise offline). Search places and forests. **Download this view** for offline use. Manage planned trails (import GPX/KML/KMZ, or draw on the map). **Past trails**: share, save as GPX, rename, or reuse as a plan. |
| **Trail** | Pick a planned trail from the dropdown, then press **START**. Trail mode shows only the offline map, your position, your track and the plan. Hold **STOP** to save. |

## Rules the app follows

- **Wi-Fi only.** Every request opens on the Wi-Fi network object itself, so mobile data cannot be used, even when Android would route traffic over it. If Wi-Fi drops, a download waits and then continues by itself.
- **Trail mode isolates the planning side.** While a trail is recording:
  - the Plan screen is destroyed (its map cache, threads and Wi-Fi watcher go with it);
  - downloads are paused, and they resume after you stop;
  - the tab bar is hidden;
  - the Trail screen never touches the network.
- **Power use:**
  - Only the GPS chip is used.
  - The screen redraws only when something changes.
  - With the screen off, fixes come in 30-second batches.
  - After about 2 minutes standing still, GPS switches off, and the hardware motion sensor turns it back on when you walk.
- **Clean tracks:**
  - Poor-accuracy fixes are dropped, and so are the first fixes after GPS restarts unless they are already sharp.
  - Impossible jumps are rejected and the track is Kalman-smoothed.
  - Standing still doesn't scribble, because the chip's Doppler speed is used.
  - Elevation comes from downloaded terrain tiles, not GPS altitude.
- **Nothing is lost.** Every point is written to disk as it arrives. If the phone kills the app, the recording continues as a new segment the next time the app starts.

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`. It is optimized and signed with the local debug key, which is fine for sideloading.

Install over USB (USB debugging must be on):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

## Data on the phone (app-private storage)

- `files/maps/*.db`: offline map packs, one SQLite file per area
- `files/plans/*.gpx`: planned trails
- `files/tracks/*.gpx`: walked trails (Past trails)
- `files/rec/current.*`: the recording in progress (crash recovery)

## Development data (on while `Dev.ENABLED = true` in `Dev.kt`)

Diagnostics are written only when the app is already awake, so logging adds no wake-ups and trail-mode power use stays the same. They go to `Android/data/app.trekmap.trail/files/dev/`:

| File | Contents |
|---|---|
| `events-YYYYMMDD.log` | Screens, tabs, searches, downloads (tiles/s, HTTP codes, retries, Wi-Fi waits), map render timings, permissions, why the last process died (HiOS kill, low memory, crash, ANR…) |
| `device.txt` | Phone and HiOS build, GNSS chip and capabilities, motion sensor, barometer, permissions, battery-optimisation state |
| `crash-*.txt` | Stack trace of any crash |
| `trails/<id>.raw.csv` | **Every GPS fix**: position, accuracy, speed, bearing, altitudes, satellites, C/N0, delivery delay, batch size, the filter's keep/drop reason, the GPS mode, plus events and battery snapshots |
| `trails/<id>.summary.txt` | A digest per recording: filter decisions, accuracy, satellites, time in each mode, rests, motion wake-ups, battery drain %/h |

The row format is documented at the top of `DevSummary.kt`.

There are two ways to get the data:

- **On the phone:** Plan › Past trails › **Share debug logs** creates one zip.
- **Over adb:**
  ```powershell
  & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pull /sdcard/Android/data/app.trekmap.trail/files/dev dev-logs
  ```

To replay real recordings through the current filter while tuning it, run these two lines in the same PowerShell window (the first sets the folder the second reads):

```powershell
$env:REPLAY_DIR="dev-logs"
```

```powershell
.\gradlew.bat testDebugUnitTest --tests "*DevTest*"
```

## Stress testing

`assembleDebug` builds **"Trail STRESS"** (`app.trekmap.trail.stress`). It installs beside the real app with its own empty data and no network (`Net.offline`), so random input can't touch real trails or download anything.

- **Edge-case unit tests:** `EdgeTest.kt` covers hostile and huge GPX/KML input, invalid GPS fixes, corridors at the poles and the 180° meridian, and truncated logs.
- **On the phone:** use a focus-checked tap script that stays out of the status bar and quick settings. Don't use Android's `monkey` on a personal phone: it can swipe down quick settings and toggle Bluetooth and other system settings.
- **Kill test:** `adb shell run-as app.trekmap.trail.stress kill -9 <pid>` while recording. Android restarts the sticky service within about 1 s, and the track resumes.
- **Afterwards:** remove the test copy with `adb uninstall app.trekmap.trail.stress`.

Map data: © OpenStreetMap contributors, OpenTopoMap (CC-BY-SA), Esri World Imagery, and the Mapzen/AWS terrain tiles.
