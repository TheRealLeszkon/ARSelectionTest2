package com.example.ardepth.depth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.ardepth.ar.DepthFrame

/**
 * Colorizes the raw ARCore depth map into an [ImageBitmap] for the debug "DEPTH" view, so you can
 * see at a glance which parts of the scene actually have usable depth (colored) versus none (black).
 *
 * The output bitmap is already rotated into the **portrait preview orientation** (using the inverse
 * of [DepthCoordinates.screenToDepth]) so, when stretched to fill the screen, a colored pixel sits
 * exactly where a tap there would sample it. Bitmap size is depthHeight × depthWidth (the 90° swap).
 *
 * Color map: a jet-style ramp over a fixed [NEAR_M]..[FAR_M] range so colors are comparable frame to
 * frame — near surfaces are red/warm, far surfaces blue/cool, no-depth pixels are black.
 */
object DepthVisualizer {

    private const val NEAR_M = 0.2f     // clamp near end of the color ramp (meters)
    private const val FAR_M = 5.0f      // clamp far end of the color ramp (meters)

    /** Reused pixel buffer so we don't allocate a new int[] every frame. */
    private var scratch: IntArray = IntArray(0)

    /**
     * Build a portrait-oriented ARGB [ImageBitmap] of the depth map. [calibrationFactor] scales the
     * raw depth to corrected meters so the color ramp matches the calibrated distances the HUD shows.
     * Not thread-confined beyond the single render loop that calls it (the scratch buffer is reused,
     * so don't call concurrently).
     */
    fun render(frame: DepthFrame, calibrationFactor: Float = 1f): ImageBitmap {
        val dw = frame.width
        val dh = frame.height
        // Portrait bitmap: width = depthHeight, height = depthWidth (the rotate-90).
        val bw = dh
        val bh = dw
        if (scratch.size != bw * bh) scratch = IntArray(bw * bh)

        // For portrait bitmap pixel (bx,by): screen maps to depth via u = by, v = dh-1-bx
        // (the inverse of screenToDepth), so the overlay aligns with tap sampling.
        for (by in 0 until bh) {
            val u = by
            val rowOut = by * bw
            for (bx in 0 until bw) {
                val v = dh - 1 - bx
                val mm = frame.raw[v * dw + u].toInt() and 0xFFFF
                scratch[rowOut + bx] =
                    if (mm == 0) 0xFF000000.toInt() else colorFor(mm / 1000f * calibrationFactor)
            }
        }

        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(scratch, 0, bw, 0, 0, bw, bh)
        return bmp.asImageBitmap()
    }

    /** Jet-style colormap: near (t→0) red, far (t→1) blue. Returns an opaque ARGB int. */
    private fun colorFor(meters: Float): Int {
        val t = ((meters - NEAR_M) / (FAR_M - NEAR_M)).coerceIn(0f, 1f)
        // Invert so near=red, far=blue (jet runs blue→red as its input goes 0→1).
        val j = 1f - t
        val r = clamp01(1.5f - kotlin.math.abs(4f * j - 3f))
        val g = clamp01(1.5f - kotlin.math.abs(4f * j - 2f))
        val b = clamp01(1.5f - kotlin.math.abs(4f * j - 1f))
        val ri = (r * 255).toInt()
        val gi = (g * 255).toInt()
        val bi = (b * 255).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun clamp01(x: Float) = if (x < 0f) 0f else if (x > 1f) 1f else x
}
