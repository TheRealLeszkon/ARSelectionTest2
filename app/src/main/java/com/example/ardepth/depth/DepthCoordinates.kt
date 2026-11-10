package com.example.ardepth.depth

/**
 * SCREEN → DEPTH-IMAGE coordinate conversion.
 *
 * This is the conversion the task specifically calls out. The camera preview fills a **portrait**
 * screen, but the ARCore depth image is delivered in the sensor's **landscape** orientation (its
 * width is the long edge). Going from a tap on the portrait screen to the right depth pixel is
 * therefore a 90° rotation, not a straight scale.
 *
 * Let the tap be normalized screen coordinates:
 *   nx = tapX / screenWidth   in [0,1], left → right
 *   ny = tapY / screenHeight  in [0,1], top  → bottom
 *
 * For a device held in portrait with the depth image in landscape, the mapping to depth pixels
 * (u along depth width, v along depth height) is:
 *
 *   u = ny * depthWidth                 // screen's vertical axis runs along the depth image's width
 *   v = (1 - nx) * depthHeight          // screen's horizontal axis runs (reversed) along its height
 *
 * (This exact mapping is the one verified on-device in the sibling ARSelectionTest PoC.) If you ever
 * run in landscape or on a device with a different sensor rotation you'd need to derive this from the
 * display rotation instead of hard-coding it — but this PoC is locked to portrait (see the manifest).
 */
object DepthCoordinates {

    /** A depth-image pixel coordinate. */
    data class DepthPixel(val u: Int, val v: Int)

    /**
     * Map a normalized screen tap (nx,ny in [0,1]) to a depth-image pixel, clamped in-bounds.
     */
    fun screenToDepth(nx: Float, ny: Float, depthWidth: Int, depthHeight: Int): DepthPixel {
        val u = (ny * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val v = ((1f - nx) * depthHeight).toInt().coerceIn(0, depthHeight - 1)
        return DepthPixel(u, v)
    }

    /**
     * Inverse mapping (DEPTH-IMAGE → normalized SCREEN), used to lay the depth visualization back
     * over the portrait preview so it lines up with where taps land:
     *   nx = 1 - v/depthHeight ,  ny = u/depthWidth
     */
    fun depthToScreen(u: Int, v: Int, depthWidth: Int, depthHeight: Int): Pair<Float, Float> {
        val nx = 1f - v.toFloat() / depthHeight
        val ny = u.toFloat() / depthWidth
        return nx to ny
    }
}
