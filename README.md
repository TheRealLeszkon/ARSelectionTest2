# ARSelectionTest2 (ARDepth)

A small Android probe for the ARCore Depth API. Point the phone at a surface, tap the screen, and it shows how far away that point is according to ARCore's raw depth.

It was built to understand raw depth before trusting it for measurement. The main finding: ARCore depth is **Z-depth** (distance to the camera plane), not straight-line distance. The two agree near the centre of the image but differ by up to about 20% at the corners.

## Features

- Tap to read raw depth in mm, Z-depth and true range at that pixel
- Colourised depth view
- Manual calibration slider (0.5x to 1.5x) and a "calibrate from a known distance" helper, saved between launches

## Build

Kotlin, ARCore, Jetpack Compose. Needs JDK 17 and an ARCore Depth-capable phone.

```bash
./gradlew :app:installDebug
```

Notes from each session are in `docs/PROGRESS.md`.
