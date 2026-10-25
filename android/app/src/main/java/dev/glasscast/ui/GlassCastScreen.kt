package dev.glasscast.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import android.os.SystemClock
import dev.glasscast.ConnectionIssue
import kotlin.math.abs
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.glasscast.GlassCastViewModel
import dev.glasscast.NetworkInfo
import dev.glasscast.Quality
import dev.glasscast.UiState

@Composable
fun GlassCastScreen(
    viewModel: GlassCastViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleAudio: () -> Unit,
    onConfirmCameraPermission: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()

  // Keep the screen on while streaming so the phone keeps relaying frames.
  val view = LocalView.current
  SideEffect { view.keepScreenOn = state.wantsStream }

  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
      if (state.isRegistered) {
        LiveScreen(viewModel, state, onDisconnect, onToggleAudio)
      } else {
        ConnectScreen(state, onConnect)
      }
    }

    state.error?.let { message ->
      AlertDialog(
          onDismissRequest = viewModel::clearError,
          confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
          title = { Text("Something went wrong") },
          text = { Text(message) },
      )
    }

    if (state.showCameraPermissionPrompt) {
      AlertDialog(
          onDismissRequest = viewModel::cancelCameraPermission,
          confirmButton = { TextButton(onClick = onConfirmCameraPermission) { Text("Open Meta AI") } },
          dismissButton = {
            TextButton(onClick = viewModel::cancelCameraPermission) { Text("Cancel") }
          },
          title = { Text("Allow camera access") },
          text = {
            Text(
                "GlassCast needs permission to use the glasses camera. You'll approve it in the Meta AI app.")
          },
      )
    }
  }
}

@Composable
private fun ConnectScreen(state: UiState, onConnect: () -> Unit) {
  Column(
      modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.weight(1f))
    Text("GlassCast", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(8.dp))
    Text(
        "See what your Ray-Ban Meta glasses see, live on this phone and on any computer on the same Wi-Fi.",
        textAlign = TextAlign.Center,
        color = Color.White.copy(alpha = 0.7f),
    )
    Spacer(Modifier.weight(1f))
    Button(
        onClick = onConnect,
        enabled = state.sdkReady && !state.isRegistering,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(if (state.isRegistering) "Connecting…" else "Connect my glasses")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        if (state.sdkReady) "You'll be sent to the Meta AI app to approve the connection."
        else "Allow Bluetooth access to connect to your glasses.",
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        color = Color.White.copy(alpha = 0.6f),
    )
  }
}

/** What the shutter does. */
private enum class CaptureMode(val label: String) {
  PHOTO("PHOTO"),
  VIDEO("VIDEO"),
}

private val Live = Color(0xFFDC2626)
private val Accent = Color(0xFFFACC15)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveScreen(
    viewModel: GlassCastViewModel,
    state: UiState,
    onDisconnect: () -> Unit,
    onToggleAudio: () -> Unit,
) {
  val viewerCount by viewModel.viewerCount.collectAsStateWithLifecycle()
  var showShare by remember { mutableStateOf(false) }
  var showGallery by remember { mutableStateOf(false) }
  var mode by remember { mutableStateOf(CaptureMode.PHOTO) }
  // Collapsed: just the live view, with a small handle to bring the controls back.
  var controlsHidden by remember { mutableStateOf(false) }
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  var toast by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(toast) {
    if (toast != null) {
      delay(1800)
      toast = null
    }
  }
  // The full panel doesn't fit in landscape: start folded, unfold to the side panel.
  LaunchedEffect(landscape) { controlsHidden = landscape }

  Box(Modifier.fillMaxSize()) {
    Preview(
        viewModel,
        state,
        fill = landscape && state.landscapeFill,
        onTap = { controlsHidden = !controlsHidden },
        onDoubleTap = {
          if (landscape) {
            val fill = !state.landscapeFill
            viewModel.setLandscapeFill(fill)
            // Filling a landscape screen keeps about a third of the portrait frame's height.
            toast =
                if (!fill) "Fit whole view"
                else if (state.quality != Quality.HIGH) "Fill screen · 720p looks sharper"
                else "Fill screen"
          }
        },
    )

    if (landscape) {
      LandscapeOverlay(
          viewModel, state, viewerCount, mode, { mode = it }, controlsHidden, { controlsHidden = it }, onDisconnect,
          onOpenGallery = { showGallery = true })
    } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
      AnimatedVisibility(!controlsHidden, enter = fadeIn(), exit = fadeOut()) {
        StatusBar(state, viewerCount, onDisconnect)
      }
      // Shown even with the controls folded away: the user needs to know.
      state.connectionIssue?.let { issue ->
        Spacer(Modifier.height(8.dp))
        ConnectionBanner(issue, viewModel::retryConnection, viewModel::dismissConnectionIssue)
      }
      Spacer(Modifier.weight(1f))
      if (controlsHidden) {
        CollapsedBar(state, onExpand = { controlsHidden = false }, Modifier.align(Alignment.CenterHorizontally))
      } else {
        // Float over the preview, above the panel, as in a camera app.
        AutoToggles(state, viewModel, Modifier.align(Alignment.End))
        Spacer(Modifier.height(8.dp))
        ZoomChips(state, viewModel)
        Spacer(Modifier.height(8.dp))
        Controls(
            viewModel = viewModel,
            state = state,
            mode = mode,
            onMode = { mode = it },
            onCollapse = { controlsHidden = true },
            onOpenGallery = { showGallery = true },
            onToggleAudio = onToggleAudio,
            onShare = { showShare = true },
        )
      }
    }

    toast?.let {
      Text(
          it,
          color = Color.White,
          fontWeight = FontWeight.SemiBold,
          modifier =
              Modifier.align(Alignment.Center)
                  .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                  .padding(horizontal = 14.dp, vertical = 8.dp),
      )
    }
  }

  if (showShare) {
    ModalBottomSheet(onDismissRequest = { showShare = false }) { ShareSheet(viewerCount) }
  }
  if (showGallery) {
    GalleryScreen(viewModel.photos, onClose = { showGallery = false })
  }
}

@Composable
private fun Preview(
    viewModel: GlassCastViewModel,
    state: UiState,
    fill: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
  // Collected here so frame updates only recompose the preview.
  val frame by viewModel.preview.collectAsStateWithLifecycle()
  val bitmap = frame
  // Read the latest callbacks without restarting gesture detection on every recomposition.
  val tap by rememberUpdatedState(onTap)
  val doubleTap by rememberUpdatedState(onDoubleTap)
  Box(
      // The whole screen, black bars included, takes pinch and tap while video is showing.
      Modifier.fillMaxSize()
          .then(
              if (bitmap == null) Modifier
              else
                  Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { tap() }, onDoubleTap = { doubleTap() })
                      }
                      .pointerInput(Unit) {
                        detectTransformGestures { _, _, scale, _ ->
                          viewModel.setZoom(viewModel.state.value.zoom * scale)
                        }
                      }),
      contentAlignment = Alignment.Center,
  ) {
    if (bitmap != null) {
      Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Live view from the glasses",
          contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
      )
    } else {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.wantsStream) {
          CircularProgressIndicator(color = Color.White)
          Spacer(Modifier.height(12.dp))
        }
        Text(state.statusText, color = Color.White.copy(alpha = 0.8f))
        state.statusHint?.let { hint ->
          Spacer(Modifier.height(8.dp))
          Text(
              hint,
              color = Color.White.copy(alpha = 0.6f),
              fontSize = 13.sp,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 40.dp),
          )
        }
      }
    }
  }
}

/** What went wrong with the glasses and what to do, with Try again when it can help. */
@Composable
private fun ConnectionBanner(issue: ConnectionIssue, onRetry: () -> Unit, onDismiss: () -> Unit) {
  Column(
      Modifier.fillMaxWidth()
          .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
          .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(
          if (issue.kind == ConnectionIssue.Kind.SETUP) Icons.Filled.Settings else Icons.Filled.Warning,
          contentDescription = null,
          tint = if (issue.kind == ConnectionIssue.Kind.DEVICE) Color(0xFFF97316) else Live,
      )
      Column(Modifier.weight(1f)) {
        Text(issue.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(issue.message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
      }
      IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = 0.7f))
      }
    }
    if (issue.canRetry) {
      Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Refresh, null)
        Spacer(Modifier.width(8.dp))
        Text("Try again")
      }
    }
  }
}

/**
 * What's left when the controls are folded away: a handle to unfold them, and the recording
 * timer so a running recording is never invisible.
 */
@Composable
private fun CollapsedBar(state: UiState, onExpand: () -> Unit, modifier: Modifier) {
  Row(
      modifier
          .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
          .padding(horizontal = 12.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    state.recordingSince?.let { RecordingTimer(it) }
    IconButton(onClick = onExpand) {
      Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Show controls", tint = Color.White)
    }
  }
}

/** Red "0:12" counter shown while recording. */
@Composable
private fun RecordingTimer(since: Long) {
  var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
  LaunchedEffect(since) {
    while (true) {
      now = SystemClock.elapsedRealtime()
      delay(500)
    }
  }
  val seconds = ((now - since) / 1000).coerceAtLeast(0)
  Text(
      "● %d:%02d".format(seconds / 60, seconds % 60),
      color = Live,
      fontSize = 14.sp,
      fontWeight = FontWeight.SemiBold,
  )
}

/** Auto enhance, stabilization and auto framing, each a round toggle. */
@Composable
private fun AutoToggles(state: UiState, viewModel: GlassCastViewModel, modifier: Modifier) {
  var caption by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(caption) {
    if (caption != null) {
      delay(1500)
      caption = null
    }
  }
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    caption?.let {
      Text(
          it,
          color = Color.White,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          modifier =
              Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                  .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    AutoToggle(Icons.Filled.AutoFixHigh, "Auto enhance", state.autoEnhance) {
      viewModel.setAutoEnhance(it)
      caption = "Auto enhance ${if (it) "on" else "off"}"
    }
    AutoToggle(Icons.Filled.PanTool, "Stabilize", state.stabilize) {
      viewModel.setStabilize(it)
      caption = "Stabilize ${if (it) "on" else "off"}"
    }
    AutoToggle(Icons.Filled.CenterFocusStrong, "Auto framing", state.autoFrame) {
      viewModel.setAutoFrame(it)
      caption = "Auto framing ${if (it) "on" else "off"}"
    }
  }
}

@Composable
private fun AutoToggle(icon: ImageVector, name: String, on: Boolean, onChange: (Boolean) -> Unit) {
  val haptics = LocalHapticFeedback.current
  Box(
      Modifier.size(40.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = if (on) 0.6f else 0.3f))
          .toggleable(value = on, role = Role.Switch) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onChange(it)
          }
          .semantics { contentDescription = name },
      contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = if (on) Accent else Color.White, modifier = Modifier.size(20.dp))
  }
}

/** Camera-style zoom presets. Pinch the preview for anything in between. */
@Composable
private fun ZoomChips(state: UiState, viewModel: GlassCastViewModel, vertical: Boolean = false) {
  if (vertical) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) { ZoomChipItems(state, viewModel) }
  } else {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
      ZoomChipItems(state, viewModel)
    }
  }
}

@Composable
private fun ZoomChipItems(state: UiState, viewModel: GlassCastViewModel) {
  val presets = listOf(1f, 1.5f, 2f, 3f)
  val zoom = state.zoom
  val onPreset = presets.any { abs(zoom - it) < 0.05f }
  if (state.autoFrame) ZoomChip("AUTO", selected = true, width = 56.dp, onClick = {})
  presets.forEach { preset ->
    val selected = !state.autoFrame && abs(zoom - preset) < 0.05f
    ZoomChip(zoomLabel(if (selected) zoom else preset), selected) {
      viewModel.setAutoFrame(false)
      viewModel.setZoom(preset)
    }
  }
  if (!state.autoFrame && !onPreset) ZoomChip(zoomLabel(zoom), selected = true, onClick = {})
}

@Composable
private fun ZoomChip(label: String, selected: Boolean, width: Dp = 48.dp, onClick: () -> Unit) {
  Box(
      Modifier.size(width = width, height = 32.dp)
          .clip(RoundedCornerShape(50))
          .background(Color.Black.copy(alpha = if (selected) 0.6f else 0.3f))
          .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text(label, color = if (selected) Accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
  }
}

private fun zoomLabel(zoom: Float): String =
    if (zoom % 1f < 0.05f) "${zoom.toInt()}×" else String.format(java.util.Locale.US, "%.1f×", zoom)

@Composable
private fun StatusBar(state: UiState, viewerCount: Int, onDisconnect: () -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
              .padding(start = 16.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(Modifier.size(8.dp).background(if (state.isStreaming) Live else Color.Gray, CircleShape))
    Text(state.statusText, color = Color.White, fontSize = 14.sp)
    if (state.isStreaming) Text("${state.fps} fps", color = Color.White, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    state.recordingSince?.let { RecordingTimer(it) }
    if (state.capturingAudio) {
      Icon(Icons.Filled.Mic, contentDescription = "Glasses microphone on", tint = Live)
    }
    if (viewerCount > 0) {
      Icon(Icons.Filled.Computer, contentDescription = "Desktop viewers", tint = Color.White)
      Text("$viewerCount", color = Color.White, fontSize = 14.sp)
    }
    Box {
      IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
      }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("Disconnect glasses") },
            onClick = {
              menuOpen = false
              onDisconnect()
            },
        )
      }
    }
  }
}

@Composable
private fun Controls(
    viewModel: GlassCastViewModel,
    state: UiState,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    onCollapse: () -> Unit,
    onOpenGallery: () -> Unit,
    onToggleAudio: () -> Unit,
    onShare: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
              .pointerInput(Unit) {
                // Swipe the panel down to fold it away.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > 120f) onCollapse() },
                ) { _, amount ->
                  dragged += amount
                }
              }
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    IconButton(onClick = onCollapse, modifier = Modifier.fillMaxWidth().height(20.dp)) {
      Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Hide controls", tint = Color.White.copy(alpha = 0.6f))
    }
    ModeSwitcher(mode, locked = state.isRecording, onMode)
    ShutterRow(viewModel, state, mode, onOpenGallery)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      Quality.entries.forEachIndexed { index, quality ->
        SegmentedButton(
            selected = state.quality == quality,
            onClick = { viewModel.setQuality(quality) },
            shape = SegmentedButtonDefaults.itemShape(index, Quality.entries.size),
        ) {
          Text(quality.label)
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
          onClick = { if (state.wantsStream) viewModel.stopStreaming() else viewModel.startStreaming() },
          modifier = Modifier.weight(1f).height(52.dp),
          colors =
              if (state.wantsStream) ButtonDefaults.buttonColors(containerColor = Live)
              else ButtonDefaults.buttonColors(),
      ) {
        Icon(if (state.wantsStream) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.wantsStream) "Stop" else "Start live view", maxLines = 1)
      }
      OutlinedButton(onClick = onToggleAudio, modifier = Modifier.height(52.dp)) {
        Icon(
            if (state.audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription =
                if (state.audioEnabled) "Turn off glasses microphone" else "Turn on glasses microphone",
            tint = if (state.audioEnabled) Live else Color.White,
        )
      }
      OutlinedButton(onClick = onShare, modifier = Modifier.height(52.dp)) {
        Icon(Icons.Filled.Computer, null)
        Spacer(Modifier.width(8.dp))
        Text("Desktop", maxLines = 1)
      }
    }
  }
}

/** PHOTO / VIDEO, switched by tapping a label or swiping sideways. Locked while recording. */
@Composable
private fun ModeSwitcher(mode: CaptureMode, locked: Boolean, onMode: (CaptureMode) -> Unit, vertical: Boolean = false) {
  val haptics = LocalHapticFeedback.current
  fun select(next: CaptureMode) {
    if (locked || next == mode) return
    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    onMode(next)
  }
  val labels: @Composable () -> Unit = {
    CaptureMode.entries.forEach { item ->
      Text(
          item.label,
          color = if (item == mode) Accent else Color.White.copy(alpha = 0.7f),
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = 1.5.sp,
          modifier =
              Modifier.clickable(enabled = !locked) { select(item) }
                  .padding(horizontal = 8.dp, vertical = 4.dp)
                  .semantics { selected = item == mode },
      )
    }
  }
  if (vertical) {
    Column(
        Modifier.pointerInput(locked, mode) {
          var dragged = 0f
          detectVerticalDragGestures(
              onDragStart = { dragged = 0f },
              onDragEnd = { if (abs(dragged) > 40f) select(if (dragged < 0) CaptureMode.VIDEO else CaptureMode.PHOTO) },
          ) { _, amount ->
            dragged += amount
          }
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      labels()
    }
  } else {
    Row(
        Modifier.fillMaxWidth().pointerInput(locked, mode) {
          var dragged = 0f
          detectHorizontalDragGestures(
              onDragStart = { dragged = 0f },
              onDragEnd = { if (abs(dragged) > 40f) select(if (dragged < 0) CaptureMode.VIDEO else CaptureMode.PHOTO) },
          ) { _, amount ->
            dragged += amount
          }
        },
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
    ) {
      labels()
    }
  }
}

/** Latest photo on the left, the shutter in the middle. */
@Composable
private fun ShutterRow(viewModel: GlassCastViewModel, state: UiState, mode: CaptureMode, onOpenGallery: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) { GalleryButton(viewModel, onOpenGallery) }
    Shutter(viewModel, state, mode)
    Spacer(Modifier.weight(1f))
  }
}

/** Latest photo or recording; opens the gallery. */
@Composable
private fun GalleryButton(viewModel: GlassCastViewModel, onOpenGallery: () -> Unit) {
  val thumbnail by viewModel.photos.latestThumbnail.collectAsStateWithLifecycle()
  IconButton(
      onClick = onOpenGallery,
      modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f)),
  ) {
    val image = thumbnail
    if (image != null) {
      Image(
          bitmap = image.asImageBitmap(),
          contentDescription = "Photos",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(Icons.Filled.PhotoLibrary, contentDescription = "Photos", tint = Color.White)
    }
  }
}

/**
 * Landscape: status and banners on the left, a slim capture panel on the right. Quality, sound,
 * desktop sharing and the auto toggles stay in portrait.
 */
@Composable
private fun LandscapeOverlay(
    viewModel: GlassCastViewModel,
    state: UiState,
    viewerCount: Int,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    controlsHidden: Boolean,
    setControlsHidden: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onOpenGallery: () -> Unit,
) {
  Row(
      Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
      if (!controlsHidden) StatusBar(state, viewerCount, onDisconnect)
      state.connectionIssue?.let { issue ->
        Spacer(Modifier.height(8.dp))
        Box(Modifier.widthIn(max = 420.dp)) {
          ConnectionBanner(issue, viewModel::retryConnection, viewModel::dismissConnectionIssue)
        }
      }
      Spacer(Modifier.weight(1f))
      if (controlsHidden) CollapsedBar(state, onExpand = { setControlsHidden(false) }, Modifier)
    }
    if (!controlsHidden) {
      Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { ZoomChips(state, viewModel, vertical = true) }
      SidePanel(viewModel, state, mode, onMode, onCollapse = { setControlsHidden(true) }, onOpenGallery)
    }
  }
}

/** The landscape capture panel: fold handle, PHOTO / VIDEO, shutter, gallery and start/stop. */
@Composable
private fun SidePanel(
    viewModel: GlassCastViewModel,
    state: UiState,
    mode: CaptureMode,
    onMode: (CaptureMode) -> Unit,
    onCollapse: () -> Unit,
    onOpenGallery: () -> Unit,
) {
  Column(
      Modifier.width(120.dp)
          .fillMaxHeight()
          .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
          .pointerInput(Unit) {
            // Swipe the panel right to fold it away.
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = { if (dragged > 120f) onCollapse() },
            ) { _, amount ->
              dragged += amount
            }
          }
          .padding(10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    IconButton(onClick = onCollapse, modifier = Modifier.height(24.dp)) {
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Hide controls", tint = Color.White.copy(alpha = 0.6f))
    }
    ModeSwitcher(mode, locked = state.isRecording, onMode, vertical = true)
    Spacer(Modifier.weight(1f))
    Shutter(viewModel, state, mode)
    Spacer(Modifier.weight(1f))
    GalleryButton(viewModel, onOpenGallery)
    Button(
        onClick = { if (state.wantsStream) viewModel.stopStreaming() else viewModel.startStreaming() },
        modifier = Modifier.fillMaxWidth(),
        colors =
            if (state.wantsStream) ButtonDefaults.buttonColors(containerColor = Live) else ButtonDefaults.buttonColors(),
    ) {
      Icon(
          if (state.wantsStream) Icons.Filled.Stop else Icons.Filled.PlayArrow,
          contentDescription = if (state.wantsStream) "Stop live view" else "Start live view",
      )
    }
  }
}

/**
 * One shutter for both modes: a white disc takes a photo with the glasses; a red disc starts
 * recording and turns into a red square that stops it.
 */
@Composable
private fun Shutter(viewModel: GlassCastViewModel, state: UiState, mode: CaptureMode) {
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val enabled =
      when (mode) {
        CaptureMode.PHOTO -> state.isStreaming && !state.takingPhoto
        CaptureMode.VIDEO -> state.isStreaming || state.isRecording
      }
  val label =
      when (mode) {
        CaptureMode.PHOTO -> "Take a photo with the glasses"
        CaptureMode.VIDEO -> if (state.isRecording) "Stop recording" else "Record video"
      }
  val innerSize by animateDpAsState(if (mode == CaptureMode.VIDEO && state.isRecording) 28.dp else 54.dp, label = "shutter")
  val corner by animateDpAsState(if (mode == CaptureMode.VIDEO && state.isRecording) 6.dp else 27.dp, label = "corner")
  Box(
      Modifier.size(72.dp)
          .border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
          .clip(CircleShape)
          .clickable(enabled = enabled) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            when (mode) {
              CaptureMode.PHOTO -> scope.launch { viewModel.takePhoto() }
              CaptureMode.VIDEO -> if (state.isRecording) viewModel.stopRecording() else viewModel.startRecording()
            }
          }
          .semantics { contentDescription = label },
      contentAlignment = Alignment.Center,
  ) {
    val color = if (mode == CaptureMode.PHOTO) Color.White else Live
    Box(
        Modifier.size(innerSize)
            .clip(RoundedCornerShape(corner))
            .background(color.copy(alpha = if (enabled) 1f else 0.4f)))
    if (mode == CaptureMode.PHOTO && state.takingPhoto) {
      CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
  }
}

/** How to open the stream on a computer: URL, QR code and OBS/VLC address. */
@Composable
private fun ShareSheet(viewerCount: Int) {
  val url = NetworkInfo.viewerUrl(LocalContext.current)
  Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Watch on a computer", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    if (url == null) {
      Text("Connect this phone to Wi-Fi to share the live view with a computer.", textAlign = TextAlign.Center)
      return@Column
    }
    val qr = remember(url) { qrCode(url) }
    Image(
        bitmap = qr.asImageBitmap(),
        contentDescription = "QR code for $url",
        filterQuality = FilterQuality.None,
        modifier = Modifier.size(180.dp),
    )
    Text(url, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
    Text(
        "Open this address in a browser on the same Wi-Fi. For OBS or VLC use $url/stream, and $url/audio for sound.",
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
    Text(if (viewerCount == 1) "1 viewer connected" else "$viewerCount viewers connected", fontSize = 13.sp)
  }
}

private fun qrCode(text: String, size: Int = 512): Bitmap {
  val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
  val pixels = IntArray(size * size) { i ->
    if (matrix[i % size, i / size]) AndroidColor.BLACK else AndroidColor.WHITE
  }
  return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
