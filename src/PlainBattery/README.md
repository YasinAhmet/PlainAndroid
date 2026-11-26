# Plain Battery

Battery tracker for the TECNO SPARK 20 Pro+ (Android 14). It logs the battery over time and
shows which app or process burned how much, using Android's own per-app power estimates.
Package `app.plain.battery`, no INTERNET, no libraries, ~105 KB.

Build: `.\gradlew.bat testDebugUnitTest assembleRelease` (JDK from PATH; the release is signed
with the debug key). The release APK is copied to `PlainBattery.apk`.

## One-time setup over adb (needed again only after an uninstall)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r PlainBattery.apk
& $adb shell pm grant app.plain.battery android.permission.DUMP
& $adb shell pm grant app.plain.battery android.permission.PACKAGE_USAGE_STATS
& $adb shell pm grant app.plain.battery android.permission.INTERACT_ACROSS_USERS
& $adb shell appops set app.plain.battery GET_USAGE_STATS allow
```

Then open the app once: Android does not run jobs of an app that has never been opened.
The grants survive app updates (`install -r`). No system setting is changed.

Why each one: the app runs `dumpsys batterystats --checkin` itself. batterystats checks the
caller for DUMP *and* PACKAGE_USAGE_STATS (permission plus app-op), and the checkin dump lists
packages of all users, which needs INTERACT_ACROSS_USERS. All four are "development"
permissions, which is why `pm grant` works for a sideloaded app.

## What it logs

Files are in `/sdcard/Android/data/app.plain.battery/files/` (pull with `adb pull`):

| file | content |
|---|---|
| `battery.csv` | every ~15 min: level, status, plug, CURRENT_NOW (raw, units vary on this phone), voltage, temperature, charge counter (µAh), screen on/off |
| `screen.csv` | screen on/off events, read from UsageStats (no receiver kept running) |
| `apps.csv` | about once an hour, and on plug/unplug: mAh each uid burned in that interval, plus its total since the stats reset |
| `status.txt` | result of the last per-app snapshot (or the dumpsys error) |

Each log rotates to `*.1.csv` at 4 MB.

## Cost of the tracker itself

- One JobScheduler periodic job (15 min, inexact, no constraints, persisted). The system holds
  the wake lock only while the job runs; the app never takes one. In Doze the job waits for
  maintenance windows like everything else.
- A plain sample costs about 20 ms of CPU. A snapshot (the hourly dumpsys) is ~0.2 s in
  system_server plus parsing 330 KB here. That is roughly 10 s of CPU a day.
- Nothing else: no service, no runtime receiver, no timers. The boot/update receiver in the
  manifest only re-arms the job. The screen updates on open, on Refresh and on tab change.
- The Apps list shows the tracker's own row in green ("this app"), so its cost is measured by
  the same method as everybody else's.

### Tracking switch (Settings tab)

Tracking is on by default. **Off** cancels the job and disables the boot/update receiver
(component state), so nothing of the app runs in the background; the switch is saved and the
job, boot and app updates all respect it. While off, the app still takes one reading (and, on
the Apps/Settings tab, a snapshot) when you open it, so the screen is current. **On** enables the
receiver and schedules the job again.

## Screen

Four tabs (the last one used is remembered); each number appears in one place only, and only
the visible tab is built.

- **Now:** level, state (charging / on battery for how long), time of the reading, temperature,
  voltage; on battery "≈ N h left" at the pace of the last ~3 h of this discharge (charge
  counter), and how long it would last idle with the screen off (standby current from
  screen-off stretches). Below, the level chart (only the time that has samples, up to 36 h).
- **Apps:** one list, with a switch between **Last N h** (on battery, two snapshots from
  `files/history`, kept 30 h) and **Since unplug** ("Last run" while charging, "Since full
  charge" after a stats reset). Each app shows **% of the battery** (its mAh ÷ battery
  capacity), a bar relative to the biggest app, and the mAh in small text. Tap a row for the
  screen / background split, uid and packages. Above the list: what the whole phone used in the
  period (measured) and what the apps add up to (estimate). Apps under 0.1 % are hidden.
  "By component" opens the device-wide hardware totals (screen, radio, ...).
- **History:** one card per day with the level lost (−) and gained (+) that day; tap a day for
  its readings (time, level, charging / screen on). Today is open.
- **Settings:** the tracking switch, whether per-app data works (last snapshot result and a
  check of each of the four grants, with the adb lines for any that are missing), the battery
  capacity in use and where it came from, and the log folder.

Battery capacity: the `pws` line of the checkin dump (the power profile's battery capacity,
5000 mAh on this phone). Fallback when there is no snapshot: charge counter ÷ level from the
last reading (only at 20 % or more). Without either, the Apps tab shows mAh.

Note: while you look at Plain Battery, Android charges the screen to it like any foreground
app, so its own row grows when it's open; its background cost is the part without screen.

## How accurate is the per-app ranking?

The numbers are Android's own estimates (the same that feed Settings → Battery), not a meter per
app. Limits:

- **Estimates, not measurements.** The phone measures only the total current (coulomb counter,
  shown as "measured"). Per-app mAh is computed from each app's CPU time, wake locks, radio
  activity, GPS, screen time and so on, multiplied by the per-component power figures in the
  phone's `power_profile.xml`. Transsion's power profile decides how good those are, so the
  apps estimate will not add up exactly to the measured mAh.
- **Screen time is charged to the app in front.** WhatsApp's 245 mAh had 214 mAh of screen in
  it. That's correct accounting, but it's the display burning power while you use the app, not the app
  misbehaving in the background. The background part is total minus screen.
- **Shared and system uids.** Everything running as Android system (uid 1000) is one row, and
  so are packages that share a uid. Kernel work and wakeups that can't be pinned on an app go
  to "Kernel / root processes" or to the hardware rows (`idle`, `cell`, ...).
- **Hardware rows overlap with the app rows.** They are device-wide totals per component;
  don't add them to the app list.
- **Since unplug.** batterystats only counts while on battery and resets after a full charge.
  The app keeps a snapshot taken while on power as the "unplug" baseline. After a reset it
  shows "Since full charge" instead.
- **Resolution.** Snapshots are hourly (plus on plug/unplug), so `apps.csv` tells you which hour
  something burned, not which minute. The battery timeline is every ~15 min, later in Doze.
- **Removed apps.** Apps uninstalled since the reset still show by uid ("Uninstalled app").
- **Side effect.** Right after a reset, the first `--checkin` call returns the saved previous
  period once and deletes it (it is meant for Google's daily checkin upload). The app and the
  PC script detect that and ask again, but Google's copy of that one previous period is then gone.

Things the platform does not give a normal app at all: per-process current, per-app
measured energy (would need the hidden `BatteryStatsManager.getBatteryUsageStats()` system API,
reachable only with a hidden-API policy change, which is a system setting, so not used).

## PC side

`tools\pull-batterystats.ps1` pulls the same checkin over adb, maps uids to package names
(including packages removed for the user) and appends to `logs\batterystats-pc.csv`, with the
mAh since the previous pull. Run it whenever the phone is connected:

```powershell
.\tools\pull-batterystats.ps1            # -KeepRaw also saves the full dump in logs\raw
```
