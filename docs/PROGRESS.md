# ARDepth — Progress & Session Handoff

> **Read this first each session.** Minimal ARCore **Depth API** probe PoC. Its ONLY job:
>
> **Point the phone at a box/chair/table → tap the screen → read how far that surface is, and
> understand the RAW depth behavior.** Nothing else.

_v0.2 (2026-08-09) — added **manual depth calibration**: a `Depth Calibration` slider (0.5×–1.5×,
default 1.0×), a Calibrate helper (enter known real distance → factor = real/rawDepth), a Debug
breakdown (raw→factor→corrected→final), all applied at the single raw→metric conversion point in
`DepthMath.sample` and persisted in SharedPreferences (`ardepth_prefs / depth_calibration_factor`).
On-device verified: slider sets+persists the factor (0.82× survived a force-stop/relaunch), panels +
button render, live prefs write per change. NOT re-verified in a lit scene: live distance rescale on
slider move + calibrate-from-tap math (device was in the dark → INSUFFICIENT_LIGHT); both are pure
linear scaling on the already-verified depth pipeline. See "Depth calibration" section below._

_v0.1 baseline — first cut. Builds clean (JDK 17) and **verified on device**
(OnePlus CPH2805): Depth AVAILABLE, Tracking TRACKING, depth res 160×90; taps return sane raw mm +
Z-depth + range (Z≈Range near center, Range>Z off-axis, e.g. 2.595 vs 2.627 m); DEPTH view renders a
colorized jet map. Accuracy characterization (M4) still to do._

---

## What this is (and is NOT)

**IS:** ARCore camera preview → tap anywhere → convert the tap to a depth-image pixel → read the raw
ARCore depth (mm) → convert to a Z-depth (m) and the true straight-line range (m) → show all of it.
Plus an RGB|DEPTH toggle that colorizes the depth map, a live Depth-API status, and the tracking
state. Deliberately answers: *"how accurate is ARCore depth at different distances / surfaces?"*

**IS NOT:** anchors, world-space measurement, object detection, segmentation, ML, point clouds,
persistence, networking. All out of scope (this is the depth-only sibling of ARSelectionTest).

## Depth semantics (the important part)

- ARCore `acquireDepthImage16Bits()` → uint16 **millimeters**, row-major, `0` = no depth.
- That raw value is a **Z-depth**: perpendicular distance from the camera plane along the optical
  axis — NOT the straight-line distance to the point (equal only at the principal point).
- So the HUD shows THREE things, on purpose:
  - **Raw depth** = the ARCore value (mm),
  - **Z-depth** = raw/1000 (m), the camera-plane depth,
  - **Range** = `sqrt(Xc²+Yc²+Z²)` (m) after back-projecting through the depth-res intrinsics —
    the actual straight-line camera→point distance.
- Intrinsics are the RGB `imageIntrinsics` **scaled to the depth image resolution** (depth is much
  lower-res and has its own effective intrinsics — never use RGB fx/cx directly on it).

## Depth calibration (v0.2)

- **Where:** applied inside `DepthMath.sample()` — the single place raw mm becomes metric —
  `correctedMm = rawMm * factor` BEFORE meters/back-projection, so Z, range and the camera-frame
  point all scale uniformly (corrects the *depth measurement*, never object geometry). Same factor
  also colors the DEPTH view (`DepthVisualizer.render(frame, factor)`).
- **Factor source of truth:** `DepthViewModel.calibrationFactor`, restored on init from
  SharedPreferences `ardepth_prefs / depth_calibration_factor` (default 1.0, clamped 0.5–1.5).
- **Live update:** moving the slider calls `setCalibrationFactor` → persists + `DepthMath.recalibrate`
  rescales the stored last tap instantly (no re-tap). `Sample` keeps both raw (`zRawMeters`,
  `rangeRawMeters`) and corrected (`zMeters`, `rangeMeters`, `correctedMm`) values.
- **Calibrate helper:** enter known real distance (cm), press Calibrate →
  `factor = realMeters / zRawMeters` (raw Z at the last tap) → sets+persists the slider.
- **Debug toggle:** shows raw ARCore depth, calibration factor, corrected depth, final measurement.
- UI lives in `DepthScreen.Hud` (Slider + CalibrationPanel + RGB|DEPTH + Calibrate|Debug rows).

## Coordinate conversion (SCREEN → DEPTH pixel), portrait-locked

```
nx = tapX/screenW, ny = tapY/screenH        // normalized screen
u  = ny * depthW                            // portrait rotate-90:
v  = (1 - nx) * depthH                       //   screen Y → depth width, screen X → depth height (rev)
```
Same mapping verified on-device in ARSelectionTest. Only valid in **portrait** (manifest locks it).
The DEPTH overlay is built pre-rotated with the inverse mapping so it lines up with taps.

---

## Architecture (tiny)

```
MainActivity                    camera-permission gate → DepthScreen
 └─ DepthViewModel              only ARCore talker; owns UiState; stores latest depth for taps
     ├─ ar/DepthFrame(+Extractor)  per-frame: tracking state + raw depth + depth-res intrinsics
     ├─ depth/DepthCoordinates     SCREEN ↔ DEPTH-pixel (portrait rotate-90)
     ├─ depth/DepthMath            sample + nearest-valid fallback + Z-depth + straight-line range
     └─ depth/DepthVisualizer      colorize raw depth → portrait ImageBitmap (jet ramp) for DEPTH view
 ui/DepthScreen                 ARScene (owns session, enables Depth) + tap layer + marker + HUD + toggle
 ui/UiState                     immutable snapshot (DepthStatus, tracking, lastTap, depthImage, viewMode)
```

- **Session:** `ARScene.sessionConfiguration` checks `isDepthModeSupported(AUTOMATIC)` and enables it;
  plane finding / light est / instant placement all OFF (not needed).
- **Taps** sample the latest stored `DepthFrame` on the UI thread → instant readout.
- **DEPTH view** rebuilds the colorized bitmap only while that view is showing.

---

## Build & run

- **JDK 17** (not the machine default 23):
  `export JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home`
- `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (~20 MB, arm64-v8a only).
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (device = OnePlus CPH2805;
  adb link is flaky — retry; **check versionName after install**, installs can silently keep the old build).
- package `com.example.ardepth`, app label "AR Depth", versionName `0.1-poc`.

## Milestone status

| # | Milestone | State |
|---|-----------|-------|
| M0 | Project builds (Compose + ARCore/SceneView, no ONNX) | ✅ builds clean |
| M1 | On-device: camera preview + Depth AVAILABLE + tracking | ✅ verified (CPH2805, depth 160×90) |
| M2 | Tap → raw mm + Z-depth + range readout is sane | ✅ verified (Z≈Range center, Range>Z off-axis) |
| M3 | RGB|DEPTH toggle shows sensible colorized depth map | ✅ verified (jet ramp, full coverage) |
| M4a | Internal characterization: coord math + Z↔range across FOV | ✅ verified (see below) |
| M4b | Absolute accuracy vs a tape measure at 0.5/1/2/3 m | ⏳ user's physical step |

## M4a — on-device characterization (2026-08-09, CPH2805)

Intrinsics: RGB 640×480 fx=502.81 fy=503.32 cx=319.09 cy=239.36 → depth-res (×0.25,×0.1875)
fx=125.70 fy=94.37 cx=79.77 cy=44.88. Depth image 160×90.

- **Coordinate + back-projection math is exact.** Hand-checked logged taps against
  `range = z·√(((u−cx)/fx)²+((v−cy)/fy)²+1)`: corner (75,32) 2.809→2.837 m ✓, far corner (10,11)
  1.078→1.292 m ✓ (match to the mm).
- **Depth coverage:** 16/16 grid taps over a matte office scene returned `valid=true`,
  `neighbor=false` (no nearest-valid fallback needed on those surfaces).
- **Z vs straight-line range diverges radially**, exactly as geometry predicts (range = z/cosθ):
  ~0.7–1% near image center, up to **~20% at the screen corners** (e.g. z 1.078 m → range 1.292 m).
  This is precisely why the raw depth value must NOT be read as straight-line distance off-axis.

Still to do (M4b) needs a physical tape measure — only the user can do it: point at box/chair/table
at 0.5/1/2/3 m, compare Range to the tape, and note drift + where valid=no (thin/shiny/dark edges).

## Verify checklist (on device)

1. Launch → grant camera → preview appears; HUD shows `Depth: AVAILABLE`, `Tracking: TRACKING`.
2. Tap a flat wall ~1 m away → Range ≈ tape measure; Z-depth ≤ Range; raw mm ≈ Range*1000 near center.
3. Tap screen corner on the same wall → Range noticeably > Z-depth (off-axis divergence sanity check).
4. Tap a thin/shiny/dark edge → expect `valid=no` or `(nearby pixel)` — that's the raw sparsity.
5. Toggle DEPTH → near surfaces red, far blue, no-depth black; colored region tracks the scene.
6. Compare readout to a tape measure at 0.5 / 1 / 2 / 3 m on box/chair/table; note where it drifts.
