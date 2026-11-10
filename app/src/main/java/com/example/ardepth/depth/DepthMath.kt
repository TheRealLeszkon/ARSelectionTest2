package com.example.ardepth.depth

import com.example.ardepth.ar.DepthFrame
import kotlin.math.sqrt

/**
 * Turns a raw ARCore depth sample into real distances, doing the conversion **correctly** rather
 * than assuming "raw value == meters".
 *
 * Two facts drive everything here:
 *  1. The raw value is an integer in **millimeters** → divide by 1000 for meters.
 *  2. That value is a **Z-depth**: the perpendicular distance from the camera plane to the surface
 *     along the optical axis. It is NOT the straight-line distance from the camera to the point
 *     (except exactly at the principal point). To get the true straight-line **range** we invert the
 *     pinhole model to find the 3D point in the camera frame, then take its length.
 *
 * **Depth calibration.** ARCore depth can be biased (e.g. consistently overshooting). We correct it
 * at exactly this conversion point — the single place raw depth becomes metric — by scaling the raw
 * millimeter value by a user-tuned [DepthMath.Sample.calibrationFactor] BEFORE meters/back-projection:
 *
 *        correctedMm = rawMm * factor
 *        Z  = correctedMm / 1000                     // meters, perpendicular (camera-plane) depth
 *        Xc = (u - cx) * Z / fx                      // right   (camera frame, meters)
 *        Yc = (v - cy) * Z / fy                      // down
 *        range = sqrt(Xc² + Yc² + Z²)                // straight-line distance camera → point
 *
 * Because every metric quantity is linear in the corrected mm, the factor scales Z, range and the
 * whole camera-frame point uniformly — it corrects the depth measurement, never the object geometry.
 *
 * `fx,fy,cx,cy` MUST be the intrinsics at depth resolution (provided by [DepthFrame]).
 */
object DepthMath {

    /** Everything computed for one sampled depth pixel, both raw and calibration-corrected. */
    data class Sample(
        val u: Int,
        val v: Int,
        /** Raw ARCore value in millimeters, UNCORRECTED (0 means "no depth here"). */
        val rawMm: Int,
        /** True if this pixel had a real depth value (rawMm != 0). */
        val valid: Boolean,
        /** True if we had to borrow a nearby pixel because the exact tapped pixel was empty. */
        val fromNeighbor: Boolean,
        /** The calibration factor applied to produce the corrected values below. */
        val calibrationFactor: Float,
        // ---- Uncalibrated (factor = 1.0) reference values, for the debug readout ----
        /** Perpendicular depth in meters from the RAW value = rawMm/1000. */
        val zRawMeters: Float,
        /** Straight-line range in meters from the RAW value. */
        val rangeRawMeters: Float,
        // ---- Calibration-corrected values (what the app reports) ----
        /** Corrected perpendicular (camera-plane) depth in meters = zRawMeters * factor. */
        val zMeters: Float,
        /** Corrected straight-line distance camera→point in meters (the "final measurement"). */
        val rangeMeters: Float,
        /** The corrected point in the camera frame (meters), for reference/debug. */
        val cameraX: Float,
        val cameraY: Float,
        val cameraZ: Float,
    ) {
        /** Corrected millimeter depth = rawMm * factor (the "corrected depth" debug line). */
        val correctedMm: Int get() = (rawMm * calibrationFactor).toInt()
    }

    /**
     * Sample [frame] at depth pixel (u,v), applying [calibrationFactor] at the metric conversion. If
     * that exact pixel has no depth, spiral outward up to [searchRadius] pixels for the nearest valid
     * one (ARCore depth is sparse — the tapped pixel is often empty even when its neighbors are fine).
     */
    fun sample(frame: DepthFrame, u: Int, v: Int, calibrationFactor: Float, searchRadius: Int = 6): Sample {
        var su = u
        var sv = v
        var mm = frame.rawAt(u, v)
        var borrowed = false

        if (mm == 0) {
            val found = nearestValid(frame, u, v, searchRadius)
            if (found != null) {
                su = found.first; sv = found.second
                mm = frame.rawAt(su, sv)
                borrowed = true
            }
        }

        if (mm == 0) {
            // No depth anywhere in the neighborhood.
            return Sample(
                u = u, v = v, rawMm = 0, valid = false, fromNeighbor = false,
                calibrationFactor = calibrationFactor,
                zRawMeters = 0f, rangeRawMeters = 0f,
                zMeters = 0f, rangeMeters = 0f, cameraX = 0f, cameraY = 0f, cameraZ = 0f,
            )
        }

        // ---- Correction applied HERE, at the raw-depth → metric conversion point. ----
        val zRaw = mm / 1000f
        val z = zRaw * calibrationFactor                 // corrected perpendicular depth (m)
        val xc = (su - frame.cx) * z / frame.fx
        val yc = (sv - frame.cy) * z / frame.fy
        val range = sqrt(xc * xc + yc * yc + z * z)
        // Uncorrected reference range (factor cancels out, so scale the corrected one back down).
        val rangeRaw = if (calibrationFactor != 0f) range / calibrationFactor else 0f

        return Sample(
            u = su, v = sv, rawMm = mm, valid = true, fromNeighbor = borrowed,
            calibrationFactor = calibrationFactor,
            zRawMeters = zRaw, rangeRawMeters = rangeRaw,
            zMeters = z, rangeMeters = range, cameraX = xc, cameraY = yc, cameraZ = z,
        )
    }

    /**
     * Re-apply a new [factor] to an already-computed [sample] without re-reading depth. Used for
     * live slider updates: every corrected quantity is linear in the factor, so we just rescale from
     * the stored raw values. Cheap and exact — identical to re-running [sample] with the new factor.
     */
    fun recalibrate(sample: Sample, factor: Float): Sample {
        if (!sample.valid) return sample.copy(calibrationFactor = factor)
        val old = sample.calibrationFactor
        val scale = if (old != 0f) factor / old else 1f
        return sample.copy(
            calibrationFactor = factor,
            zMeters = sample.zRawMeters * factor,
            rangeMeters = sample.rangeRawMeters * factor,
            cameraX = sample.cameraX * scale,
            cameraY = sample.cameraY * scale,
            cameraZ = sample.cameraZ * scale,
        )
    }

    /** Spiral-ish nearest-valid search: scan rings of increasing radius, return the first hit. */
    private fun nearestValid(frame: DepthFrame, u: Int, v: Int, maxR: Int): Pair<Int, Int>? {
        for (r in 1..maxR) {
            for (dv in -r..r) {
                for (du in -r..r) {
                    // Only the outer ring at this radius (skip interior already scanned).
                    if (kotlin.math.max(kotlin.math.abs(du), kotlin.math.abs(dv)) != r) continue
                    if (frame.rawAt(u + du, v + dv) != 0) return (u + du) to (v + dv)
                }
            }
        }
        return null
    }
}
