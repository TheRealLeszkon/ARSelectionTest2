package com.example.ardepth.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.example.ardepth.ar.DepthFrame
import com.example.ardepth.ar.DepthFrameExtractor
import com.example.ardepth.depth.DepthCoordinates
import com.example.ardepth.depth.DepthMath
import com.example.ardepth.depth.DepthVisualizer
import com.google.ar.core.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place that talks to ARCore. Owns [UiState].
 *
 * Flow each frame ([onFrame], on the AR render loop):
 *   - read the tracking state,
 *   - acquire the raw depth image (+ depth-resolution intrinsics),
 *   - stash it as the "latest depth" so a tap can sample it immediately,
 *   - derive the coarse [DepthStatus], and (only in DEPTH view) rebuild the colorized depth bitmap.
 *
 * Taps ([onTap]) come from the UI thread and sample the latest stored depth frame directly, applying
 * the current [DepthMath] calibration factor, so the distance readout appears instantly.
 *
 * **Calibration** is a single scalar applied at the raw-depth → metric conversion (inside
 * [DepthMath.sample]). It is persisted in [android.content.SharedPreferences] so it survives restarts,
 * and moving the slider live-rescales the last measurement without needing a new tap.
 */
class DepthViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Latest depth frame, published by the render loop and read by taps. */
    private val latestDepth = AtomicReference<DepthFrame?>(null)

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // Restore the persisted calibration factor (default 1.0 = no correction).
        val saved = prefs.getFloat(KEY_FACTOR, 1.0f).coerceIn(MIN_FACTOR, MAX_FACTOR)
        _uiState.update { it.copy(calibrationFactor = saved) }
    }

    /** Set from ARScene's sessionConfiguration once ARCore reports Depth API support. */
    fun setDepthSupported(supported: Boolean) {
        _uiState.update { it.copy(depthSupported = supported) }
    }

    fun setTrackingFailure(reason: String?) {
        _uiState.update { it.copy(trackingFailure = reason) }
    }

    fun toggleViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setCalibrationMode(on: Boolean) {
        _uiState.update { it.copy(calibrationMode = on) }
    }

    fun setDebugMode(on: Boolean) {
        _uiState.update { it.copy(debugMode = on) }
    }

    fun setRealDistanceCmInput(text: String) {
        _uiState.update { it.copy(realDistanceCmInput = text) }
    }

    // ---- Calibration ----------------------------------------------------------------------------

    /**
     * Set the calibration factor (clamped), persist it, and **live-rescale** the last measurement so
     * the displayed distance updates immediately — no re-tap required.
     */
    fun setCalibrationFactor(factor: Float) {
        val f = factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
        prefs.edit().putFloat(KEY_FACTOR, f).apply()
        _uiState.update { state ->
            val rescaledTap = state.lastTap?.let {
                it.copy(sample = DepthMath.recalibrate(it.sample, f))
            }
            state.copy(calibrationFactor = f, lastTap = rescaledTap)
        }
    }

    /**
     * Compute a factor from a known real distance and the last measured RAW depth, then apply it:
     *
     *     calibrationFactor = realDistance / rawDepth
     *
     * "rawDepth" is the uncorrected ARCore value (Z-depth) at the last tap, so afterwards the
     * corrected depth at that point equals the real distance the user entered. Requires a valid last
     * tap and a positive real distance.
     *
     * @return true if a factor was applied, false if it couldn't (no valid tap / bad input).
     */
    fun calibrateFromLastTap(realDistanceCm: Float): Boolean {
        val tap = _uiState.value.lastTap ?: return false
        val rawZ = tap.sample.zRawMeters
        if (!tap.sample.valid || rawZ <= 0f || realDistanceCm <= 0f) return false
        val realMeters = realDistanceCm / 100f
        val factor = realMeters / rawZ
        Log.i(TAG, "CALIBRATE real=${realMeters}m rawZ=${rawZ}m -> factor=${"%.4f".format(factor)}")
        setCalibrationFactor(factor)
        return true
    }

    // ---- Frame loop -----------------------------------------------------------------------------

    /** Called every frame (~30 fps) from the AR render loop. */
    fun onFrame(frame: Frame) {
        val trackingState = DepthFrameExtractor.trackingState(frame)
        val state = _uiState.value
        val supported = state.depthSupported

        val depth = if (supported == true) DepthFrameExtractor.extractDepth(frame) else null
        if (depth != null) latestDepth.set(depth)

        val status = when {
            supported == false -> DepthStatus.UNAVAILABLE
            depth != null -> DepthStatus.AVAILABLE
            latestDepth.get() != null && trackingState == "TRACKING" -> DepthStatus.AVAILABLE
            else -> DepthStatus.INITIALIZING
        }

        // Only pay for the depth visualization when the DEPTH view is showing.
        val depthImage = if (state.viewMode == ViewMode.DEPTH) {
            (depth ?: latestDepth.get())?.let { DepthVisualizer.render(it, state.calibrationFactor) }
        } else null

        _uiState.update {
            it.copy(
                trackingState = trackingState,
                depthStatus = status,
                depthWidth = depth?.width ?: it.depthWidth,
                depthHeight = depth?.height ?: it.depthHeight,
                depthImage = depthImage,
            )
        }
    }

    /**
     * Handle a tap at normalized screen coords (nx,ny in [0,1]): convert to a depth pixel, sample the
     * latest depth frame with the current calibration factor, and publish it for the HUD + marker.
     */
    fun onTap(nx: Float, ny: Float) {
        val factor = _uiState.value.calibrationFactor
        val depth = latestDepth.get()
        if (depth == null) {
            // No depth yet — still record the tap position so the marker + a "no depth" note show.
            _uiState.update {
                it.copy(
                    lastTap = TapResult(
                        screenNx = nx, screenNy = ny,
                        sample = DepthMath.Sample(
                            u = -1, v = -1, rawMm = 0, valid = false, fromNeighbor = false,
                            calibrationFactor = factor,
                            zRawMeters = 0f, rangeRawMeters = 0f,
                            zMeters = 0f, rangeMeters = 0f, cameraX = 0f, cameraY = 0f, cameraZ = 0f,
                        ),
                    )
                )
            }
            return
        }

        // SCREEN → DEPTH-PIXEL (portrait rotate-90), then sample + calibrate + back-project.
        val px = DepthCoordinates.screenToDepth(nx, ny, depth.width, depth.height)
        val sample = DepthMath.sample(depth, px.u, px.v, factor)

        Log.i(
            TAG,
            "TAP screen=(${"%.3f".format(nx)},${"%.3f".format(ny)}) depthPx=(${px.u},${px.v}) " +
                "raw=${sample.rawMm}mm factor=${"%.3f".format(factor)} " +
                "corrected=${sample.correctedMm}mm valid=${sample.valid} neighbor=${sample.fromNeighbor} " +
                "z=${"%.3f".format(sample.zMeters)}m range=${"%.3f".format(sample.rangeMeters)}m"
        )

        _uiState.update {
            it.copy(lastTap = TapResult(screenNx = nx, screenNy = ny, sample = sample))
        }
    }

    private companion object {
        const val TAG = "DepthViewModel"
        const val PREFS = "ardepth_prefs"
        const val KEY_FACTOR = "depth_calibration_factor"
        const val MIN_FACTOR = 0.5f
        const val MAX_FACTOR = 1.5f
    }
}
