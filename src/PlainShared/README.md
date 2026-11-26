# PlainShared

Code shared by the merged Plain apps. Each of them includes this folder as its `:shared` Gradle module:

```kotlin
// settings.gradle.kts
include(":shared")
project(":shared").projectDir = file("../PlainShared")
```

| Package | What |
|---|---|
| `app.plain.hub` | Tabs. `Hub.setup()` in `Application.onCreate`, `HubActivity` as the launcher icon (opens the last used tab), `Hub.wrap()` for the dropdown header on top of each tab, `Hub.gate()` at the top of each tab's `onCreate`. Every tab is its own Activity in its own task. |
| `app.plain.migrate` | Copies a former app's data (Plain Trekking → Plain Maps, …) through that app's `MigrateProvider`. `ImportActivity` asks first: Copy / Start fresh / Not now. Never overwrites, never changes the former app. |
| `app.plain.lang` | English / Turkish (`tr("…", "…")`). Same as `PlainAndroid/shared/PlainLang.kt`. |
| `app.plain.update` | "Check for updates", handed to Plain Manager (`app.plain.setup`). |

`bridge/` holds the two files every **former** app carries in its last release: `MigrateProvider.kt` (hands its data to
the merged app, same signing key only, read only) and `PlainMoved.kt` (the "this app moved" notice). They are not part
of the module.

| Merged app | Package | Tabs (former apps) |
|---|---|---|
| Plain Manager | `app.plain.setup` | Setup, Battery (`app.plain.battery`), Uninstaller (`app.plain.uninstaller`) |
| Plain Maps | `app.plain.maps` | Istanbul, Ankara (`app.plain.maps.ankara`, not imported: never released), Trekking (`app.trekmap.trail`) |
| Plain Camera | `app.plain.camera` | Camera, Gallery (`app.plain.gallery`) |
| Plain Media | `app.plain.download` | Music (`app.localmusic`), Youtube, Spotify (placeholder) |

Each merged app keeps the package of the app with the most data, so that app updates in place.
