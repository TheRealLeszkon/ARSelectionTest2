package com.example.ardepth.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.example.ardepth.depth.DepthMath

/** Which live layer fills the screen: the normal camera, or the colorized depth map. */
enum class ViewMode { RGB, DEPTH }

/** Coarse Depth-API availability shown continuously in the HUD. */
enum class DepthStatus {
    /** Device / ARCore doesn't support the Depth API at all. */
    UNAVAILABLE,
    /** Supported, but ARCore isn't tracking yet / has no depth for recent frames. */
    INITIALIZING,
    /** Supported and we're receiving depth frames. */
    AVAILABLE,
}

/** A tap result, kept for the HUD readout + on-screen marker. */
data class TapResult(
    /** Normalized screen position of the tap (for drawing the marker). */
    val screenNx: Float,
    val screenNy: Float,
    /** The depth sample (raw mm, validity, z-depth, straight-line range, camera-frame point). */
    val sample: DepthMath.Sample,
)

/** Immutable UI snapshot rendered by DepthScreen; owned solely by [DepthViewModel]. */
data class UiState(
    /** null = still probing; true/false once ARCore reports Depth API support. */
    val depthSupported: Boolean? = null,
    val depthStatus: DepthStatus = DepthStatus.INITIALIZING,
    /** ARCore camera tracking state name (TRACKING / PAUSED / STOPPED / …). */
    val trackingState: String = "—",
    val trackingFailure: String? = null,
    /** Resolution of the last depth image we read (for the HUD), or 0 if none yet. */
    val depthWidth: Int = 0,
    val depthHeight: Int = 0,

    val viewMode: ViewMode = ViewMode.RGB,
    /** Colorized depth map for the DEPTH view; null in RGB mode or before first depth frame. */
    val depthImage: ImageBitmap? = null,

    /** The most recent tap and its measured distance, or null if nothing tapped yet. */
    val lastTap: TapResult? = null,

    // ---- Depth calibration ----
    /** Multiplier applied to raw depth before it becomes metric. 1.0 = no correction. Persisted. */
    val calibrationFactor: Float = 1.0f,
    /** Whether the calibration helper panel is open. */
    val calibrationMode: Boolean = false,
    /** Text the user types for the known real distance, in centimeters (raw text field content). */
    val realDistanceCmInput: String = "",
    /** Whether the debug breakdown (raw / factor / corrected / final) is shown. */
    val debugMode: Boolean = false,
)
