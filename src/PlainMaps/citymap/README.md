# Plain Istanbul Map and Plain Ankara Map (Android)

Offline city maps built around public transport, with no satellite view and no Street View. One codebase builds two separate apps (product flavors):

| App | Package | Buses | Map and rail |
|---|---|---|---|
| **Plain Istanbul Map** | `app.plain.maps` | İETT | BBBike's Istanbul extract, plus Overpass for the outer districts |
| **Plain Ankara Map** | `app.plain.maps.ankara` | EGO | openstreetmap.fr's Central Anatolia extract |

Everything that differs between the cities is in `City.kt`. Each app shows:

- the **bus web**: every bus route drawn faintly, so busy corridors glow brighter;
- metro, tram, Marmaray, funicular, cable-car and ferry lines in their own colours;
- streets, water, parks, stops and places (shops, food, pharmacies…).

## Using it

| Do this | Get this |
|---|---|
| **Tap anywhere on the map** | Every line that stops within 100 m (press and hold to grow the circle up to 400 m, release to pick), drawn on the map in matching colours. Each row shows the nearest stop, its distance and the next departures. |
| **Tap a stop** (zoom 15+) | The lines at that stop, sorted by next departure. |
| **Tap a place dot** (zoom 16+) | Address, opening hours, phone (tap to dial), website, plus the lines within 400 m. |
| **Tap a line in a list** | Its route and stops on the map, with the stop list and the next time at each stop. Also: **Other direction**, **Variants**, **Timetable** (weekdays / Saturday / Sunday, at the chosen stop). |
| **Search bar** | Lines (e.g. `500T`, `M2`), stops, places and streets. Turkish letters are optional (`kadikoy` finds Kadıköy). |
| **Route button** (top bar, next to Settings) | The contact route finder. Set From and To on the map, from your last location, or with **Route from/to here** on a stop, place or tapped point. It returns the 3 best options. Options are ordered by fewest changes first, then shortest ride. Lines that don't run in the next two hours (night lines by day) go to the back. Tap an option for its legs and changes, and to save it. |
| **My routes ▾** | Saved routes. Tap one to open it (the route is found again and the saved line sequence is selected) or delete it. |
| **Location button** | Off at every start. The last fix is remembered across restarts and drawn grey once it is more than a minute old. One tap starts GPS and follows you. A second tap after panning centres on you again; a tap while following turns it off. Location also stops whenever the app leaves the screen. |
| **Settings › Full sync** | Downloads everything again over Wi-Fi. |
| **Settings › Situational sync** | Fetches the operator's current announcements (shown with ⚠ on lines, stops and in lists) and today's departure times for all bus lines, without downloading the map. Istanbul: İETT's web services, about 30 s. Ankara: EGO's announcement list and timetable pages, a few minutes. |
| **Settings › Lines on the map** | Bus web thickness and brightness, metro/tram/ferry thickness, and the thickness of selected lines. |
| **Search filters** | Toggle Lines, Stops, Places and Streets, and pick place types from a list. Filters start from "everything" for each new search. Every matching place in the current view glows on the map (not just the listed ones), and listed results on screen are marked "in view". |
| **Query packs** (list icon in the search bar) | Saved groups of names searched together, e.g. "Shops - common": a101, migros, bim, şok. Whole-word matching, so "şok" doesn't find "sokak". Long-press a pack to edit or delete; "+ Pack" on a search adds that query to a pack. |

## Rules the app follows

- **Offline.** Only the Sync button uses the network.
- **Wi-Fi only.** Every request is opened on the Wi-Fi network object itself, so mobile data is never used.
- **No battery use when idle.** Nothing runs in the background. The map redraws only when something changes. GPS runs only while its button is on and the app is on screen. A sync runs as a foreground service (wake lock and Wi-Fi lock) only while it lasts.
- **A sync can be interrupted.** Finished steps are kept, and the next sync continues from there (within 3 days). The old data stays usable until the new data is complete.

## Where the data comes from: Istanbul

| Data | Source | Notes |
|---|---|---|
| Bus lines, stops, timetables | İBB open data, `iett-gtfs-verisi` | Uses `stop_times.zip`: the CSV copy is cut at Excel's 1,048,576-row limit. Coordinates are repaired from the spreadsheet mangling (`410.191.700.005.564` → 41.0191700005564), and double-encoded UTF-8 is repaired (`KADIKÃ–Y` → KADIKÖY). Only the first and last stop of a trip have times; times at the stops in between are estimated by distance. |
| Bus route shapes | İBB open data, `iett-hat-guzergahlari` | 257 MB GeoJSON, matched to the timetable by route code (99.6 %). If neither file has changed since the last sync, the bus data is copied over instead of downloaded again. |
| City map, places, rail/ferry lines | BBBike weekly extract `Istanbul.osm.pbf` (45 MB) | Covers 28.66–29.27 E, 40.90–41.27 N. Read by `Pbf.kt` in three streaming passes. |
| Outer districts | Overpass API, squares of 0.25° | Best effort. If the server is busy a square is left out, Settings says how many, and the next sync tries again. |

**How the route finder works (`Router.kt`).** It doesn't calculate walking or follow streets. It starts from the stops within 500 m of A. Each round rides every line reachable so far to all of its later stops. Lines "contact" where their stops are within 250 m of each other, which is where a change can happen. A journey ends at a stop within 500 m of B. At most 2 changes are allowed. On the full İETT network it takes about 0.1 s.

**Situational sync.** It uses İETT's SOAP services `Duyurular.asmx/GetDuyurular_json` (announcements) and `PlanlananSeferSaati.asmx/GetPlanlananSeferSaati_json` (planned departures per line). The planned times are keyed by the same route codes as the published timetable. Trip lengths keep the published timetable's typical duration. The sync works on a copy of the database and swaps it in when done.

Metro, tram and ferry lines have no timetables (OpenStreetMap has none). Public holidays are not in the İETT timetable.

## Where the data comes from: Ankara

| Data | Source | Notes |
|---|---|---|
| Bus lines, stop order, departure times | ego.gov.tr/tr/hareketsaatleri | About 660 lines. The page lists them, and posting `hat_no1=<code>` returns a line's name, trip length (minutes), weekday/Saturday/Sunday departures and its stops in order with EGO stop numbers. Fetched three at a time. EGO runs Saturday times on holidays. |
| Stop positions | OpenStreetMap bus stops | OSM stops carry the EGO stop number as `ref`, which places about 59 % of the stops. Others are matched by name near their placed neighbours on the line, or estimated between those neighbours. About 91 % of stops get a position; stops without one are left out of their lines (`ego_stats` in Settings says how many). |
| Route shapes | OSM bus route relations | Matched by EGO line code (about 340 lines). Other lines are drawn stop to stop. |
| Map, places, metro, Ankaray, Başkentray, cable car | openstreetmap.fr `central_anatolia-latest.osm.pbf` (daily, about 120 MB) | Cut down while it is read to the box EGO's stops span, plus 3 km (about 38.9–40.55 N, 31.85–33.57 E; see below). Peak memory is about 230 MB. |
| Announcements | ego.gov.tr/tr/duyurular | The line numbers a title starts with get a ⚠ notice. "NNNNN NUMARALI EGO DURAĞI" titles go to that stop. |

**The map is as big as the transit.** Each sync works out the map's area from the stops:
- **Ankara:** the sync first reads EGO's lines. It then reads OSM bus stops across Ankara province and places EGO's stops. The area becomes the box around every placed stop, plus 3 km. A stop only counts if it lies within 15 km of a neighbour on its line, so a stop number that also exists in another city can't stretch the map. The rest of the extract is cut to that box.
- **Istanbul:** the box grows to every İETT stop, plus 3 km, and is never smaller than the province box.

Lines without an OSM route shape follow the streets: the shortest drivable path between their known stops (`RoadGraph.kt`). Stops EGO lists without a position are spread along that path in stop order.

EGO line codes are grouped by number: 102-1 and 102-2 are two variants of line 102, and the code is shown in the headsign.

## Where the data comes from: İzmir and Kocaeli (since Plain Maps 3.6)

Both publish a plain GTFS feed (`City.Bus.GTFS`, `Sync.gtfsTransit`). Only bus routes are kept (route_type 3, 11, 7xx);
metro, tram, İZBAN, rail and ferry lines come from the OpenStreetMap extract as in the other cities. The map's area is the
box around every stop plus 3 km (`fitToStops`), cut from the regional extract while it is read (no Overpass).

| City | Buses | Map and rail |
|---|---|---|
| İzmir | ESHOT `https://www.eshot.gov.tr/gtfs/bus-eshot-gtfs.zip` (one zip, about 18 MB: 421 lines, 11.7k stops, 61k trips, shapes.txt) | openstreetmap.fr `aegean-latest.osm.pbf` (about 90 MB) |
| Kocaeli | Kocaeli BB open data, dataset `e2a87342-…` on `kavisacikveri.kocaeli.bel.tr/api/public/OpenDataPublic/<id>` (CC BY). No zip: each file is an attachment, `…/attachments/<attachmentId>/download`; the ids change when the city re-uploads, so the dataset JSON is read every sync. stop_times.txt is about 100 MB. 404 bus lines, 8.5k stops, 47k trips. | openstreetmap.fr `marmara-latest.osm.pbf` (about 170 MB) |

Quirks: ESHOT's calendar dates run out (the 2026-08 feed ended 2026-10-05) but only the weekday pattern is used, so it
keeps working; Kocaeli's trip_headsign is the line's name in both directions, so the last stop's name is shown instead;
shapes.txt rows are not in sequence order (they are sorted). Real-file tests: `ESHOT_ZIP` and `KOCAELI_DIR` in RealDataTest.

## Build

```powershell
.\gradlew.bat testIstanbulDebugUnitTest testAnkaraDebugUnitTest assembleIstanbulRelease assembleAnkaraRelease
```

The outputs are `app\build\outputs\apk\<city>\release\app-<city>-release.apk`, copied to `PlainIstanbulMap.apk` and `PlainAnkaraMap.apk` in this folder. They're signed with the shared Plain key (`A:\Projects\PlainSigning`).

The real-data tests run when these variables point at downloaded files (otherwise they are skipped):

- `GTFS_DIR`: a folder with `stops.csv`, `routes.csv`, `trips.csv`, `calendar.csv`, `stop_times.zip` and `shapes.geojson`
- `OSM_CELL`: an Overpass JSON answer
- `OSM_PBF`: `Istanbul.osm.pbf`
- `IETT_PLAN`: a saved `GetPlanlananSeferSaati_json` answer
- `ANKARA_PBF`: `central_anatolia-latest.osm.pbf`
- `EGO_DIR`: saved EGO pages (`_index.html`, `_duyurular.html`, `<code>.html`)

## Files

The app's private storage holds:

- `files/data.db`: everything the app shows (SQLite)
- `files/sync/`: the sync in progress (`data-new.db`, `work.db`) and `last-error.txt`

A log of every sync is in `Android/data/<package>/files/sync.log`. Read Istanbul's with (use `app.plain.maps.ankara` for Ankara):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell cat /sdcard/Android/data/app.plain.maps/files/sync.log
```

## Code map

| File | Role |
|---|---|
| `Sync.kt`, `SyncService.kt` | The sync steps, and the foreground service that runs them |
| `City.kt` | Everything city-specific: area, sources, texts |
| `Gtfs.kt`, `Text.kt` | İETT feed reader and its repairs |
| `Ego.kt`, `EgoFetch.kt` | EGO page parsing, stop placement, network building; fetching |
| `Pbf.kt`, `Osm.kt`, `Json.kt` | OSM extract reader, Overpass reader, streaming JSON |
| `MapBuild.kt` | Which OSM elements are kept, and place categories |
| `MapData.kt`, `Clip.kt`, `Coast.kt` | Map cells at three levels of detail; clipping and simplifying; coastline to land polygons |
| `TileRenderer.kt` | Draws 256-px tiles from cells, on background threads |
| `MapView.kt` | Pan and zoom, with live overlays drawn on top |
| `Store.kt` | Database schema, queries and timetable arithmetic |
| `Router.kt` | Contact route finder (line network and rounds) |
| `Situational.kt` | Situational sync (İETT or EGO announcements and times) |
| `Style.kt` | The line-look multipliers from Settings |
| `MainActivity.kt` | Search bar, bottom sheet screens, settings |

Map data © OpenStreetMap contributors (ODbL). Bus data: İBB Açık Veri Portalı (İETT); EGO Genel Müdürlüğü (ego.gov.tr).
