# Plain Camera: Basic / Advanced plan

Status: phases 1–5 built and installed on 2026-11-24 (defaults taken for the open questions: flash in Advanced, no ML model, Look not in Basic). Phase 6 (2nd lens checks, focus peaking, ML model) not done. README.md describes what was built. Differences from this plan: no crop frame (the preview always has the photo's shape), and the analysis uses a 160-px copy of the preview instead of an extra camera stream.

## What the phone's camera can do (read from `dumpsys media.camera`)

| | Rear main (id 0) | Rear 2nd (id 2) | Front (id 1) |
|---|---|---|---|
| Sensor | 4000×3000 (12 MP) | 1600×1200 (2 MP), fixed focus | 3264×2448 (8 MP), fixed focus |
| Level | FULL, MANUAL_SENSOR, MANUAL_POST_PROCESSING | MANUAL_SENSOR | MANUAL_SENSOR |
| ISO / shutter | 100–19200 / 1/10000 s–30 s | | |
| EV compensation | −2…+2 in 1/10 steps | same | same |
| White balance | all 8 presets + manual gains | same | same |
| Focus | auto + manual, closest 10 cm | none | none |
| Tone curve | yes, 101 points (contrast/shadows on camera side, also for video) | | |
| Noise reduction / sharpening | off, fast, high quality, minimal, ZSL / off, fast, HQ | | |
| Scene modes | night, sunset, landscape, HDR (18), sports… | | |
| Face detection | yes, up to 15 faces | | |
| AE/AWB lock | yes | yes | yes |
| Colour effects | **none** (so saturation must be our own processing) | | |
| Not available | RAW/DNG, 60 fps (max 30), video or optical stabilisation | | |

## Tabs

A small `BASIC · ADVANCED` switch in the top bar (remembered).

**Basic** – exactly: aspect dropdown and Photo/Video. The shutter, last-photo thumbnail and front/back flip stay (they are controls, not settings). Everything runs fully automatic at the best resolution for the aspect. Flash moves to Advanced (open question 1).

Basic always ignores the advanced values; they are kept and come back when you return to Advanced. So Basic is a safe "just take the picture" mode. Calls from other apps (Trekmap's photo button, etc.) always open in Basic.

**Advanced** – the preview stays full screen. A row of chips above the shutter:

`EV · ISO · Shutter · WB · Focus · Look · Guides · Quality · ⚡ · Lens`

Tapping a chip opens one slider or option row in place (OLED style, outlined, amber value). A chip whose value is not Auto shows it in amber (e.g. `ISO 400`). "Reset" at the end of each row; "Reset all" in Quality.

## Advanced contents

### 1. Exposure and camera-side look (photo and video)
- **EV** −2…+2, snaps to 1/3 stops.
- **Lock**: hold the preview to lock exposure and white balance (AE/AWB lock), tap to unlock. A small `AE-L` tag shows while locked.
- **ISO** Auto / 100…6400 (higher allowed but marked "noisy"), **Shutter** Auto / 1/4000…4 s (video caps at 1/30). Setting either switches to manual exposure; the other follows automatically as far as the camera allows.
- **WB** Auto, Daylight, Cloudy, Shade, Tungsten, Fluorescent, Warm fluorescent, Twilight, plus a Kelvin slider (2500–9000 K via manual colour gains).
- **Focus** Auto / manual slider (∞ … 10 cm), with an optional focus-peaking overlay (later phase).
- **Contrast and shadows** through the camera's tone curve, so they apply to video too.
- **Sharpening** and **noise reduction**: Auto / Off / Low / High.
- **Scene**: Auto, Night, Sunset, Landscape, HDR. These are hints to the phone's processing; how much they change is up to the Tecno firmware, so they get tested before being kept.

### 2. Look (photo only)
The camera has no saturation control, so Look is our own processing:
- **Saturation**, **Warmth**, **Tint**, **Vibrance**, plus presets: Natural, Vivid, Muted, B&W, Warm.
- Preview: a colour matrix on the preview layer (GPU, close to zero cost), so you see the result live.
- Saved photo: the same matrix applied to the JPEG after capture (decode, process in strips, re-encode). About 0.3–1 s per 12 MP photo, on the existing background saver thread. The shutter stays instant.
- With the Natural/neutral look the JPEG is saved untouched, exactly as now.
- In video, the Look sliders are greyed out with "photo only". Recoloring video needs an OpenGL pipeline, which is a large job. Contrast/WB/EV still work in video.

### 3. Guides (composition)
- **Grid**: off, thirds, golden ratio, centre cross, diagonals.
- **Level**: a horizon line from the gravity sensor; turns amber when within 1°.
- **Crop frame** for the chosen aspect when the sensor shape differs.
- **Faces**: optional thin brackets on detected faces (the camera's own face detection, no ML library).

### 4. Quality
- **Resolution** (moved here from the top bar): the current per-aspect list.
- **JPEG quality**: 85 / 90 / 95 (default) / 100.
- **Video**: resolution and bitrate Standard / High. 30 fps only; the phone has no 60.
- **Save location** stays DCIM/Camera.

### 5. Flash and lens
- **⚡** off / auto / on (photo), torch (video): moved from Basic.
- **Lens**: Main / 2nd rear camera (2 MP, fixed focus; likely macro or depth). What it actually captures gets checked with a test shot first. It is offered only if it gives a usable picture.

## Guidance ("what's wrong with this shot")

Fully offline: the app has no internet permission and keeps it that way. There are two tiers.

### Tier 1 – built in, recommended
Rules over data the phone already produces, plus a small analysis stream. It covers the examples you gave:

| Hint | How it is detected |
|---|---|
| "Too dark – add light or use flash" | the camera reports flash required, or ISO ≥ 3200 with a long exposure |
| "Low light – hold steady, photo will be noisy" | ISO and exposure time from each frame's result |
| "Sun in frame – flare likely, move it to the edge or shade the lens" | a small fully clipped, very bright blob in the brightness map |
| "Highlights blown – lower EV" | more than about 4% of pixels clipped (brightness histogram) |
| "Subject backlit – tap it to expose for it, or use flash" | centre/focus area much darker than the edges and bright clipped edges |
| "Flat light" | very narrow histogram |
| "Shaking – hold still" | gyroscope rotation rate |
| "Soft – tap to focus" | sharpness measure (edge variance) on the analysis frame, plus the focus state |
| "Tilted 4°" | gravity sensor |
| "Face cut off / too close to the top" | face rectangles from face detection |
| "Face in shadow" | brightness inside the face box compared with the whole frame |

- The analysis stream is 320×240 frames at 3–4 per second, only while Advanced is open with guidance on, and paused with the existing idle timeout.
- One hint at a time, by priority. A hint must persist about 1 s before it shows and stays at least 2 s, so the line doesn't flicker.
- Hints never block the shutter.
- **Ready dot**: a small dot on the shutter turns green when steady, focused, level within 2° and nothing clipped – "now is a good moment".
- Optional **steady shutter**: after you press, wait up to 1 s for the phone to be still, then capture.
- **After the shot**: the thumbnail briefly shows a short verdict, e.g. "8% blown", from the saved frame's histogram.

The rules are a pure Kotlin `Advisor` with unit tests on synthetic histograms, like `Tags` in the gallery.

### Tier 2 – optional on-device model (open question 2)
A bundled image model (TFLite) for scene type and the main subject. It could add hints like "subject not on a thirds point" or "busy background", and pick a scene automatically.
- Still offline, but adds roughly 3–10 MB and a dependency, and uses more battery.
- Recommendation: build Tier 1 first, then decide whether anything is missing.

## Build phases
1. Settings model (`CamSettings`, pure + unit tested), BASIC/ADVANCED switch, slim Basic, chip row + option rows; move resolution and flash.
2. Camera-side controls: EV, lock, ISO/shutter, WB + Kelvin, focus, tone curve, NR/sharpening, scene, JPEG quality.
3. Guides overlay: grids, level, crop frame, faces.
4. Look: live preview matrix, JPEG post-process, presets.
5. Guidance Tier 1: analysis stream, sensors, `Advisor`, hint line, ready dot, steady shutter, post-shot verdict.
6. Optional: 2nd lens, focus peaking, Tier 2 model.

Then README, build, install (focus/TrailService checks as before), phone test. Test shots are taken only with consent and deleted afterwards.

## Budget
- No new permissions. Sensors need none, and there is still no INTERNET.
- APK growth about 30–50 KB for phases 1–5 (it is about 99 KB now).
- Battery: Basic is unchanged. In Advanced, analysis and sensors run only while it is visible.

## Open questions
1. Flash: move it to Advanced (strict "aspect and photo/video only"), or keep a small ⚡ in Basic for night use?
2. Tier 2 on-device model: skip for now (recommended) or include?
3. Should the Look also apply to Basic photos? Recommended no: Basic stays plain.
