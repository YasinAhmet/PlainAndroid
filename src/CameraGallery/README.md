# Plain Camera + Plain Gallery

> **Frozen (2026-11-26).** This app is now part of Plain Camera (A:\Projects\PlainCamera). Work happens there. This project only
> builds the last release of the old app, which hands its data to the new one (`app/plain/migrate/MigrateProvider.kt`,
> copied from A:\Projects\PlainShared\bridge) and tells the user it moved (`PlainMoved.kt`).

Two small, fully offline Android apps that replace the stock camera and gallery on the TECNO
SPARK 20 Pro+. Kotlin, no AndroidX or other libraries, plain Views and Canvas. No INTERNET
permission, no analytics. Release APKs are about 185 KB and 110 KB.

**OLED styling.** The phone has an AMOLED screen, where only lit pixels use power. So all UI
chrome is pure black: buttons, dropdown popups, dialogs and empty grid cells are black with a
thin #333 outline instead of grey fills. Main text is #E4E4E4 (not pure white), secondary text
#A6A6A6, inactive labels #747474, and the accent a slightly darker amber (#E6B422). The gallery
viewer's bars are solid black (tap the photo to hide them). The camera's top and bottom bars stay
lightly see-through because they lie over the live preview, and hiding part of the frame would
spoil framing. Everywhere else they are black on black anyway.

| App | Package | Permissions |
|---|---|---|
| Plain Camera | `app.plain.camera` | CAMERA, RECORD_AUDIO |
| Plain Gallery | `app.plain.gallery` | READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, MANAGE_MEDIA (optional, user-granted) |

## Build and install

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
adb install -r camera\build\outputs\apk\release\camera-release.apk
adb install -r gallery\build\outputs\apk\release\gallery-release.apk
```

The release builds are signed with the local debug key. `local.properties` must use forward
slashes. Copies of the APKs sit next to this file (`PlainCamera.apk`, `PlainGallery.apk`).

## Plain Camera (Camera2 API)

CameraX needs AndroidX, so the camera uses Camera2 directly.

- Top bar: an **aspect** dropdown (4:3, 16:9, 1:1, Full ≈ 20:9, only shapes the sensor offers)
  and the **BASIC | ADVANCED** switch (remembered; other apps' capture requests start in Basic).
- **Basic**: aspect and Photo/Video only. Everything is automatic at the biggest size for the
  aspect (video: 1080p), no flash. Basic ignores every Advanced setting, which are kept for later.
- Captures the moment the shutter (or a volume key) is pressed, with no confirm step. With flash
  on, or flash auto in the dark, it meters the pre-flash first (at most 1.5 s).
- Video: H.264 with AAC audio, 30 fps, a recording timer, and 540p up to 1440p (whatever the
  sensor and encoder support). It defaults to 1080p.
- Saves to `DCIM/Camera` through MediaStore (`IMG_yyyyMMdd_HHmmss_SSS.jpg`,
  `VID_….mp4`). This phone's camera HAL turns the pixels upright itself. When a HAL only asks
  for rotation, the app writes the EXIF orientation, and it adds EXIF dates with a UTC offset
  when they are missing.
- **Tap to focus = focus lock.** A tap focuses once on that point and locks it: an amber
  square with an `AF-L` tag marks it, and the focus stays there however the phone moves (no
  continuous refocusing, no timeout, the shutter never refocuses). Tap the square again for
  continuous auto focus; tap anywhere else to move the lock. The metering spot follows the tap.
  The lock survives switching photo / video, aspect, size, Basic / Advanced and starting a
  recording (after such a rebuild it is held as a fixed lens distance when the camera allows manual focus), and is dropped when the
  camera closes (flip, lens change, idle pause, leaving the app). A grey square means the lens
  couldn't focus there. Works in Basic and Advanced; Advanced's manual focus overrides it (a tap
  then only meters, for 5 s, as on a fixed-focus front camera).
- Front/back flip and the last settings remembered per camera and mode.
- **Last-capture thumbnail** opens the shot in **Plain Gallery** (any gallery when Plain Gallery
  isn't installed). There you swipe back and forth through the camera roll (`DCIM/Camera`,
  photos and videos, newest first) and can delete one after another; after a delete the next one
  shows. Back returns to the camera, which re-checks its newest capture on every return (one tiny
  media-store query, off the main thread): trashed shots drop out and the thumbnail moves to the
  one before, or disappears when none is left.
- Battery: the preview runs at 1080p or less, and the camera closes after 2 minutes without
  touch ("tap to continue") and whenever the app leaves the screen.
- Answers `ACTION_IMAGE_CAPTURE`, `ACTION_VIDEO_CAPTURE` and their secure variants, writes to
  `EXTRA_OUTPUT` (or returns a small bitmap) and goes straight back to the calling app. It also
  answers `STILL_IMAGE_CAMERA` and `VIDEO_CAMERA`.

### Advanced tab

A row of chips above the shutter; tapping one opens its option rows (tap it again to close). A
chip that isn't on Auto shows its value in amber. Chips the current camera can't do are hidden.

| Chip | What it does |
|---|---|
| Profiles | Named snapshots of the settings: all Advanced settings (flash strength included), flash for photo and for video, aspect and size for each, and photo / video. **Save as…** (a name; an existing name asks to overwrite), tap a profile to **load** it at once, **Overwrite “name”** for the active one, **hold** a profile to rename or delete it. The chip shows the active profile. Stored in the app's private settings. |
| EV | Exposure compensation −2…+2 in 1/3 stops. **Hold the preview** to lock exposure and white balance (`AE-L` tag); hold again, or tap the tag, to unlock. (A tap only locks focus.) |
| ISO / Shutter | Manual exposure (ISO 100–6400, 1/4000 s – 4 s; video stops at 1/30). Set one and the other follows the light last measured (a shutter picked that way stops at 1/4 s, so the preview never freezes); set both for full manual. |
| WB | Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Warm fluorescent, Twilight, and **Kelvin** 2500–9000 K (from this camera's own daylight colour gains). |
| Focus | Auto (tap to lock focus, see above) or a manual distance slider (∞ … 10 cm), which overrides tap focus. |
| Tone | Contrast and Shadows through the camera's tone curve (works in video too), Sharpen and Noise reduction (Auto / Off / Low / High). |
| Look | Photos only: Natural, Vivid, Muted, B&W, Warm, or Saturation / Warmth / Tint / Vibrance. Shown live on the preview; applied to the saved JPEG afterwards (EXIF kept). Natural saves the camera's JPEG untouched. |
| Scene | Night, Sunset, Landscape, HDR, Sports, Portrait: hints to the phone's processing. Picking one resets manual ISO / Shutter / WB, and the other way round. |
| Guides | Grid (thirds, golden, centre, diagonals), Level (turns amber within 1°), Faces (brackets from the camera's face detection), Hints, Steady shutter. |
| Quality | Resolution, JPEG quality (85–100) or video bitrate (Standard / High), Reset all. |
| Flash | Off / Auto / On; Torch in video. **Strength** slider for On and Torch, only on Android 15+ with a flash that reports strength levels; hidden otherwise (so **not on this Android 14 phone**). A set strength fires a plain flash at that level without pre-flash metering (Android ignores strength while auto exposure drives the flash); Reset = the phone's own flash metering. |
| Lens | Main or another rear camera, when Android lists one. On this phone Android only offers apps the main rear and the front camera, so the chip stays hidden. |

**Hints** (offline, no internet): rules over the camera's own numbers (ISO, exposure time,
focus state, faces), the motion sensors, and a 160-px copy of the preview read 3 times a second.
They cover shaking, too dark, the sun or a lamp in frame (flare), backlit subject, blown
highlights, a face cut off or in shadow, low light, out of focus, tilt, headroom and flat
light. One hint at a time; it must last about 1 s to appear and stays at least 2 s. A dot on the
shutter turns green when the phone is steady, focused, level and nothing is blown. After a
photo, a short verdict appears if something was off ("Saved · 8% blown"). **Steady shutter**
waits up to 1 s after the press for the phone to be still. The sensors and the preview copy
only run while the Advanced tab is open with these options on.

## Plain Gallery

- All images and videos in MediaStore, newest first (by date taken, else file date), as a
  **fixed-page grid**: 4×6 per page in portrait (6 columns in landscape). Use Previous and Next,
  or tap the page label to jump to a page, the first page or the last. A sideways fling on the
  grid also turns the page. There is no endless scrolling.
- Thumbnails come from `ContentResolver.loadThumbnail` at cell size. The current page loads
  first, then the next page ahead of time. Full images are never decoded for the grid.
- The viewer has pinch / double-tap zoom (it decodes more detail when zoomed), swipe to the
  next or previous item (also over a playing video), and video playback (VideoView + standard
  controls). Each item shows its thumbnail at once and then sharpens.
- Share, Delete (moves the item to the system trash through Android's own consent dialog, and
  it can be restored for 30 days) and Details (name, date, folder, size, resolution, length).
- Opens photos and videos from other apps (`ACTION_VIEW`, `REVIEW`). For a MediaStore item it
  places you at that item inside the full library (reloaded if the item is newer than the last
  load), so swiping continues from there. From **Plain Camera's thumbnail** it swipes through the
  camera roll only (`DCIM/Camera`, queried fresh, caption starts with “Camera”), and deletes in a
  row move on to the next item. Needs the photo/video permission; without it only that one item
  shows.
- **Camera** button in the grid's top bar opens Plain Camera (shown when it's installed).

### Menu (⋮, top right of the grid)

- **Select**: tap items to tick them (or hold an item when *Hold to delete* is off). The top bar
  shows the count and **All on page**. The action row offers **Categorize** (bulk), **Delete**
  (bulk, one trash request for all of them) and, inside a category, **Remove** (from that
  category only). ✕ or Back ends selection.
- **Categories** (on / off): the start screen becomes folders: **All**, one folder per category
  (cover = its newest item, plus a count), and **Uncategorized**. One item can be in any number of
  categories. Hold a category folder to rename or delete it (deleting a category never deletes
  photos). **New category…** is in the menu on the folder screen, and **New…** in the categorize
  dialog. The viewer gets a **Category** button while categories are on. Categories are stored in
  the app's private storage (`files/categories.txt`, media-store ids), so they are lost if the app
  is uninstalled or its data cleared.
- **Hold to delete** (on / off): holding a photo moves it to the trash at once. An **UNDO** bar
  stays for 8 seconds, and quick deletes in a row add up to one Undo. The trash keeps items for
  30 days.

**No prompt per delete.** Android asks before any app trashes media it didn't create, unless the
app has the special access **Media management** (Android 12+). Turning *Hold to delete* on offers
a shortcut to that setting (Settings › Apps › Special app access › Media management apps ›
Plain Gallery › Allow). With it allowed, quick delete, bulk delete and the viewer's Delete need no
prompt (Undo is still there). Without it, Android asks once per delete action, and a bulk delete
gets a single prompt for the whole batch.

## Making them the defaults (do this on the phone)

**Gallery.** Android lets any app become the default for viewing photos and videos:
1. If the stock Gallery is already set to open photos by default: Settings › Apps › Gallery ›
   *Open by default* (or *Set as default*) › **Clear defaults**.
2. Open any photo, for example from Plain Camera's thumbnail or a file manager. When Android asks
   which app to use, pick **Plain Gallery** and then **Always**.
3. Put Plain Gallery on the home screen in place of the old Gallery icon.

**Camera.** Android 14 has no "default camera" setting for an installed (non-system) camera:
1. Put **Plain Camera** on the home screen or dock in place of the stock Camera icon (drag it
   from the app drawer; long-press the old icon › Remove).
2. Trekmap Trail's trail-photo button now opens Plain Camera directly.
3. Since Android 11, other apps' "take a photo" buttons (browser uploads, ID scans, some
   messengers) and the lock-screen / double-press-power camera shortcut can only open a
   **preinstalled** camera. They keep using the stock camera, so keep `com.transsion.camera`
   installed. Hiding its icon is fine.
