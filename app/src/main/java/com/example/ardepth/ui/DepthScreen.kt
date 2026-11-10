package com.example.ardepth.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.Config
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

/**
 * The whole PoC screen:
 *   - live camera via SceneView's [ARScene] (which owns the ARCore session + enables Depth),
 *   - an optional colorized **depth** overlay (RGB | DEPTH toggle),
 *   - a tap layer that measures the (calibration-corrected) distance at the tapped pixel,
 *   - a crosshair marker at the last tap,
 *   - a bottom HUD with status, the distance readout, a **Depth Calibration** slider + helper, and
 *     (in debug mode) the raw → factor → corrected → final breakdown.
 */
@Composable
fun DepthScreen(
    viewModel: DepthViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    Box(modifier = modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            cameraNode = cameraNode,
            planeRenderer = false,
            sessionConfiguration = { session, config ->
                // Check + enable the Depth API. AUTOMATIC lets ARCore use hardware depth where
                // available and fall back to depth-from-motion otherwise.
                val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode =
                    if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                config.focusMode = Config.FocusMode.AUTO
                viewModel.setDepthSupported(depthSupported)
            },
            onTrackingFailureChanged = { reason -> viewModel.setTrackingFailure(reason?.name) },
            onSessionUpdated = { _, frame -> viewModel.onFrame(frame) },
        )

        // DEPTH overlay: the colorized depth map stretched to fill the (portrait) screen. It's built
        // pre-rotated to the preview orientation, so it lines up with where taps sample.
        if (ui.viewMode == ViewMode.DEPTH) {
            ui.depthImage?.let { img ->
                Image(
                    bitmap = img,
                    contentDescription = "Depth map",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        // Tap layer: converts a tap to normalized screen coords and asks the ViewModel to measure.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        viewModel.onTap(o.x / size.width, o.y / size.height)
                    }
                }
        )

        // Marker at the last tap.
        TapMarker(ui, Modifier.fillMaxSize())

        // Bottom readout + calibration + view toggle.
        Hud(
            ui = ui,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

/** A small crosshair "+" drawn at the last tapped screen location. */
@Composable
private fun TapMarker(ui: UiState, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val tap = ui.lastTap ?: return@Canvas
        val px = tap.screenNx * size.width
        val py = tap.screenNy * size.height
        val color = if (tap.sample.valid) MarkerValid else MarkerInvalid
        val r = 34f
        drawLine(color, Offset(px - r, py), Offset(px + r, py), strokeWidth = 5f)
        drawLine(color, Offset(px, py - r), Offset(px, py + r), strokeWidth = 5f)
        drawCircle(color, radius = 6f, center = Offset(px, py))
    }
}

/** Bottom HUD: status + distance + calibration slider/helper + RGB|DEPTH + debug breakdown. */
@Composable
private fun Hud(ui: UiState, viewModel: DepthViewModel, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ---- Status ----
        val depthLine = when (ui.depthStatus) {
            DepthStatus.AVAILABLE -> "AVAILABLE"
            DepthStatus.UNAVAILABLE -> "UNAVAILABLE (device)"
            DepthStatus.INITIALIZING -> "INITIALIZING…"
        }
        val depthColor = when (ui.depthStatus) {
            DepthStatus.AVAILABLE -> StatusGood
            DepthStatus.UNAVAILABLE -> StatusBad
            DepthStatus.INITIALIZING -> StatusWait
        }
        HudLine("Depth", depthLine, valueColor = depthColor)
        HudLine("Tracking", ui.trackingState)
        ui.trackingFailure?.let { HudLine("Lost tracking", it, valueColor = StatusBad) }

        Spacer(Modifier.height(6.dp))

        // ---- Distance (final, calibration-corrected measurement) ----
        val tap = ui.lastTap
        val s = tap?.sample
        when {
            s == null -> HudLine("", "Tap anywhere to measure distance")
            !s.valid -> HudLine("Distance", "no depth here", valueColor = StatusBad)
            else -> HudLine(
                "Distance",
                "%.3f m  (%.1f cm)".format(s.rangeMeters, s.rangeMeters * 100),
                valueColor = StatusGood,
            )
        }

        // ---- Debug breakdown: raw → factor → corrected → final ----
        if (ui.debugMode) {
            Spacer(Modifier.height(4.dp))
            HudLine("— DEBUG —", "")
            if (ui.depthWidth > 0) HudLine("Depth res", "${ui.depthWidth}×${ui.depthHeight}")
            if (s != null && s.valid) {
                HudLine("Tap px", "(${s.u}, ${s.v}) depth-img" + if (s.fromNeighbor) " (nearby)" else "")
                HudLine("Raw ARCore depth", "${s.rawMm} mm  (%.3f m)".format(s.zRawMeters))
                HudLine("Calibration factor", "%.3fx".format(s.calibrationFactor))
                HudLine("Corrected depth", "${s.correctedMm} mm  (%.3f m)".format(s.zMeters))
                HudLine("Final measurement", "%.3f m range".format(s.rangeMeters))
            } else {
                HudLine("Calibration factor", "%.3fx".format(ui.calibrationFactor))
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Depth Calibration slider ----
        HudLine("Depth Calibration", "%.2fx".format(ui.calibrationFactor), valueColor = Accent)
        Slider(
            value = ui.calibrationFactor,
            onValueChange = { viewModel.setCalibrationFactor(it) },
            valueRange = 0.5f..1.5f,
        )

        // ---- Calibration helper panel ----
        if (ui.calibrationMode) {
            CalibrationPanel(ui, viewModel)
        }

        Spacer(Modifier.height(6.dp))

        // ---- Toggle rows ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToggleButton("RGB", ui.viewMode == ViewMode.RGB) { viewModel.toggleViewMode(ViewMode.RGB) }
            ToggleButton("DEPTH", ui.viewMode == ViewMode.DEPTH) { viewModel.toggleViewMode(ViewMode.DEPTH) }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToggleButton("Calibrate", ui.calibrationMode) { viewModel.setCalibrationMode(!ui.calibrationMode) }
            ToggleButton("Debug", ui.debugMode) { viewModel.setDebugMode(!ui.debugMode) }
        }
    }
}

/**
 * Calibration workflow: point the camera at a known distance, tap the object to get its raw depth,
 * type the real distance in cm, press Calibrate. The factor = realDistance / rawDepth is applied to
 * the slider (and persisted). See [DepthViewModel.calibrateFromLastTap].
 */
@Composable
private fun CalibrationPanel(ui: UiState, viewModel: DepthViewModel) {
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FFFFFF))
            .padding(12.dp),
    ) {
        Text(
            "Calibrate: tap the object at a known distance, enter it, press Calibrate.",
            color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
        Spacer(Modifier.height(6.dp))

        val s = ui.lastTap?.sample
        if (s != null && s.valid) {
            HudLine("Current raw depth", "%.3f m  (%.1f cm)".format(s.zRawMeters, s.zRawMeters * 100))
        } else {
            HudLine("", "Tap the object first to get a raw reading.")
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = ui.realDistanceCmInput,
                onValueChange = { viewModel.setRealDistanceCmInput(it.filter { c -> c.isDigit() || c == '.' }) },
                label = { Text("Real distance (cm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    val cm = ui.realDistanceCmInput.toFloatOrNull()
                    if (cm != null) viewModel.calibrateFromLastTap(cm)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
            ) {
                Text("Calibrate", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SelectedBtn else UnselectedBtn,
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HudLine(label: String, value: String, valueColor: Color = Color.White) {
    if (label.isEmpty()) {
        Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    } else if (value.isEmpty()) {
        Text(label, color = Color(0xFFBBBBBB), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    } else {
        Row {
            Text("$label: ", color = Color(0xFFBBBBBB), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

private val StatusGood = Color(0xFF00E676)
private val StatusBad = Color(0xFFFF5252)
private val StatusWait = Color(0xFFFFD54F)
private val Accent = Color(0xFF40C4FF)
private val MarkerValid = Color(0xFF00E676)
private val MarkerInvalid = Color(0xFFFF5252)
private val SelectedBtn = Color(0xFF1565C0)
private val UnselectedBtn = Color(0xFF424242)
