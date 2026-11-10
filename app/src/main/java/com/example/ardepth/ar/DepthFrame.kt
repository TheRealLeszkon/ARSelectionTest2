package com.example.ardepth.ar

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/**
 * One frame's worth of ARCore **depth** data, pulled out of an ARCore [Frame] and converted into
 * plain primitives so the ViewModel/UI never touch the ARCore SDK directly.
 *
 * The ARCore Depth API gives us a low-resolution 16-bit depth image whose pixels are **millimeters**.
 * To turn a depth pixel into a real distance we also need the camera **intrinsics scaled to the depth
 * image's resolution** (the depth image is much smaller than the RGB frame and has its *own* effective
 * intrinsics — using the RGB fx/cx on it would be wrong). This class captures all of that together.
 *
 * IMPORTANT — what the raw value means: ARCore's depth is the distance from the **camera plane**
 * along the camera's principal (optical) axis to the observed surface — i.e. a Z-depth, *not* the
 * straight-line (Euclidean) distance from the camera to the point. They are equal only at the exact
 * image center; everywhere else the straight-line range is larger and must be recovered by
 * back-projecting through the intrinsics. See [com.example.ardepth.depth.DepthMath].
 */
class DepthFrame(
    /** Raw 16-bit depth, row-major [width]*[height]; each value is millimeters (0 = no depth). */
    val raw: ShortArray,
    val width: Int,
    val height: Int,
    // Intrinsics AT DEPTH RESOLUTION (RGB intrinsics scaled down to the depth image size).
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
) {
    /** Raw millimeter value at depth-pixel (u,v), or 0 if out of bounds / no depth. */
    fun rawAt(u: Int, v: Int): Int {
        if (u < 0 || u >= width || v < 0 || v >= height) return 0
        return raw[v * width + u].toInt() and 0xFFFF   // DEPTH16 is unsigned; mask the sign bit
    }
}

/** Reads the current camera tracking state + (when tracking) the depth image off an ARCore frame. */
object DepthFrameExtractor {

    private const val TAG = "DepthFrameExtractor"
    private var loggedIntrinsics = false

    /** @return the ARCore tracking state name, e.g. TRACKING / PAUSED / STOPPED. */
    fun trackingState(frame: Frame): String = frame.camera.trackingState.name

    /**
     * Acquire this frame's depth image, or null if the camera isn't tracking yet or ARCore doesn't
     * have depth for this exact frame (common in the first frames and occasionally mid-stream — the
     * next frame usually has it).
     */
    fun extractDepth(frame: Frame): DepthFrame? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null

        return try {
            frame.acquireDepthImage16Bits().use { image ->
                val dw = image.width
                val dh = image.height
                val plane = image.planes[0]
                // DEPTH16: native byte order (little-endian on ARM), one uint16 per pixel in
                // millimeters. Rows may be padded, so honor rowStride/pixelStride rather than
                // assuming a tightly packed buffer.
                val buf = plane.buffer.order(ByteOrder.nativeOrder())
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val shorts = ShortArray(dw * dh)
                var o = 0
                for (y in 0 until dh) {
                    val rowBase = y * rowStride
                    for (x in 0 until dw) {
                        shorts[o++] = buf.getShort(rowBase + x * pixelStride)
                    }
                }

                // Depth-resolution intrinsics = the CPU-image intrinsics scaled from their native
                // resolution down to the depth image's resolution. The depth image has its own
                // (smaller) fx/fy/cx/cy; never use the RGB values directly on it.
                val ci = camera.imageIntrinsics
                val dim = ci.imageDimensions
                val fl = ci.focalLength
                val pp = ci.principalPoint
                val sx = dw.toFloat() / dim[0]
                val sy = dh.toFloat() / dim[1]

                if (!loggedIntrinsics) {
                    Log.i(
                        TAG,
                        "Depth ${dw}x$dh | RGB ${dim[0]}x${dim[1]} " +
                            "fx=${fl[0]} fy=${fl[1]} cx=${pp[0]} cy=${pp[1]} " +
                            "(depth intrinsics = these * $sx,$sy)"
                    )
                    loggedIntrinsics = true
                }

                DepthFrame(
                    raw = shorts,
                    width = dw,
                    height = dh,
                    fx = fl[0] * sx,
                    fy = fl[1] * sy,
                    cx = pp[0] * sx,
                    cy = pp[1] * sy,
                )
            }
        } catch (e: NotYetAvailableException) {
            null
        }
    }
}
