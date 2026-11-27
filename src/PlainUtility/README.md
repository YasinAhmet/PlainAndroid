# Plain Utility

Offline everyday tools in one app (package `app.plain.utility`, accent violet `#A594F9`), two tabs on the shared
Hub (`../PlainShared`):

- **Clock** (`:clock`, `app.plain.clock`): Focus (pomodoro 25/5/15, long break every 4, auto-continue), Timer
  (presets, typed lengths like `1:30` / `1h 20m` / `10 dk`, +1 min) and Stopwatch (laps, best lap in the accent).
  Swipe the dial sideways = next/previous mode, double-tap the time = start/pause, hold ▶ = reset,
  hold the time in Timer = type a length. Other apps' "set a timer" (`SET_TIMER`) opens it.
- **Notes** (`:notes`, `app.plain.notes`): quick text and voice notes with reminders. The field is in the thumb bar;
  a time written in the text ("in 20 min", "pasta 10 dk", "tomorrow 9", "yarın 18:30", "friday 10", "3 Dec") becomes
  the reminder (chip above the bar, tap to drop) or tap the bell. Empty field = mic: hold to talk (release saves,
  slide left cancels) or tap to record hands-free. Text shared from other apps becomes a draft.
  Notes live in `files/notes.json` + `files/notes/<id>.m4a` (private; Share goes through `NotesProvider`).

Battery: nothing runs in the background. A running pomodoro/timer or a timed note is one exact alarm
(`USE_EXACT_ALARM`) at its end; the running notification's countdown is Android's chronometer. Screens tick only while
visible. Alarms are set again after a restart or update (`ClockBoot`, `NotesBoot`).

Build: `.\gradlew.bat testDebugUnitTest assembleRelease` (APK in `app/build/outputs/apk/release`, copied to
`PlainUtility.apk`). Not in `PlainAndroid/catalog.json` yet, so not published.
