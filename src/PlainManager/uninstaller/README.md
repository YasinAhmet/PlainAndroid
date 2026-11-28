# Plain Uninstaller

A minimal, offline Android app (package `app.plain.uninstaller`) for decluttering a phone: pick apps from a list
and remove them in one go. Part of the Plain family: Kotlin, plain Android framework, no AndroidX, pure-black
OLED look. No INTERNET permission and nothing runs in the background.

## What it does

- **Apps tab**: every installed app with icon, label, package name and a checkbox. Filter chips
  (User apps / System apps / Plain apps), search, "Select all visible", "Clear", and an
  "Uninstall N selected" button. A confirm dialog lists everything selected first.
- **Safety**: core packages (Android, System UI, Settings, phone/telecom, package installer, permission
  controller, `com.android.providers.*`, Bluetooth, Wi-Fi, network stack, ...), the current launcher, the current
  keyboard, Shizuku and this app are never listed (`Safety.kt`); neither are system config/resource packages
  (`android.*`, `*.auto_generated_rro_*`, `com.(google.)android.*.resources`), nor any home app while no default home
  is set. Google Play services, Play Store, Google Services Framework, WebView, the share sheet, the default
  dialer/SMS apps, other home apps (the stock one often runs Recents and gestures), the preinstalled camera (the only
  one other apps' "take a photo" can use) and system overlays carry an orange "Risky" tag and need a second
  confirmation. "Select all visible" leaves risky apps out. Hold a row for Open / App info / Copy package name.
- **Restore tab**: system apps removed for user 0 (`pm list packages -u` minus `pm list packages`, plus the app's
  own log in `files/removed_system_apps.csv`). Restores with `cmd package install-existing <pkg>`. Without
  Shizuku it shows the matching `adb shell ...` commands to run from a PC.
- **Setup tab**: a live checklist (re-checked whenever the app comes back to the front) that gets Shizuku going.
- **⋮ menu**: How to declutter, Setup, Restore, and "Check for updates (vX)" via Plain Setup (`PlainUpdate.kt`).

## Two ways to uninstall

1. **Without Shizuku** (user apps only): Android's own uninstall dialog opens for each selected app in turn
   (`ACTION_UNINSTALL_PACKAGE` + `REQUEST_DELETE_PACKAGES`). System apps can't be removed this way; the app says so
   and points to Setup.
2. **With Shizuku** (all apps, silent, in bulk, with a progress dialog and a per-app result):
   - user apps: `pm uninstall --user 0 <pkg>` (like Android's dialog: a copy in a work profile or app twin stays)
   - system apps: `pm uninstall -k --user 0 <pkg>` (the APK stays on the system image, so it can be restored;
     a factory reset brings it back anyway).

## Shizuku flow

Shizuku (`moe.shizuku.privileged.api`) runs a helper with adb ("shell") rights. The Setup tab walks through:

1. Install Shizuku (Play Store, or https://shizuku.rikka.app/download/).
2. Turn on Developer options (tap Build number 7 times).
3. Turn on Wireless debugging (Wi-Fi must be connected). Android 10 and older: start Shizuku from a PC over USB.
4. Open Shizuku, pair once ("Pairing" + the code from Wireless debugging), then tap "Start".
   After a reboot only "Start" is needed again.
5. Allow Plain Uninstaller in Shizuku's permission dialog.

The app uses the official client library `dev.rikka.shizuku:api` / `provider` **13.1.5** (the only library), with
`rikka.shizuku.ShizukuProvider` declared in the manifest. Commands run through the private
`Shizuku.newProcess(...)` called by reflection as `sh -c <cmd>` (`Shiz.kt`, kept by `app/proguard-rules.pro`).
Package names are validated before they go into a command.

## Build

```powershell
cd A:\Projects\PlainUninstaller
.\gradlew.bat testDebugUnitTest assembleRelease
copy app\build\outputs\apk\release\app-release.apk PlainUninstaller.apk
```

The release build is minified, shrunk and signed with the shared Plain key when
`A:/Projects/PlainSigning/signing.properties` exists (debug key otherwise). Unit tests cover the plain-Kotlin
logic: safety classification, `pm list packages` parsing, the restore diff, command building and the removal log.
