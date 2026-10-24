package dev.glasscast.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

  Box(Modifier.fillMaxSize()) {
    Preview(viewModel, state)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
      StatusBar(state, viewerCount, onDisconnect)
      Spacer(Modifier.weight(1f))
      ShutterRow(viewModel, state, onOpenGallery = { showGallery = true })
      Spacer(Modifier.height(12.dp))
      Controls(
          state = state,
          onToggle = { if (state.wantsStream) viewModel.stopStreaming() else viewModel.startStreaming() },
          onQuality = viewModel::setQuality,
          onToggleAudio = onToggleAudio,
          onShare = { showShare = true },
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

/** Latest photo on the left, the glasses shutter in the middle. */
@Composable
private fun ShutterRow(viewModel: GlassCastViewModel, state: UiState, onOpenGallery: () -> Unit) {
  val thumbnail by viewModel.photos.latestThumbnail.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val canShoot = state.isStreaming && !state.takingPhoto

  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) {
      IconButton(
          onClick = onOpenGallery,
          modifier =
              Modifier.size(52.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(Color.White.copy(alpha = 0.12f)),
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
    Box(
        modifier =
            Modifier.size(72.dp)
                .border(4.dp, Color.White.copy(alpha = if (state.isStreaming) 1f else 0.4f), CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (canShoot) 1f else 0.4f))
                .then(
                    if (canShoot) {
                      Modifier.clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch { viewModel.takePhoto() }
                      }
                    } else {
                      Modifier
                    }),
        contentAlignment = Alignment.Center,
    ) {
      if (state.takingPhoto) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp)
    }
    Spacer(Modifier.weight(1f))
  }
}

@Composable
private fun Preview(viewModel: GlassCastViewModel, state: UiState) {
  // Collected here so frame updates only recompose the preview.
  val frame by viewModel.preview.collectAsStateWithLifecycle()
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val bitmap = frame
    if (bitmap != null) {
      Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = "Live view from the glasses",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
      )
    } else {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.wantsStream) {
          CircularProgressIndicator(color = Color.White)
          Spacer(Modifier.height(12.dp))
        }
        Text(state.statusText, color = Color.White.copy(alpha = 0.8f))
      }
    }
  }
}

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
    Box(
        Modifier.size(8.dp)
            .background(if (state.isStreaming) Color(0xFFDC2626) else Color.Gray, CircleShape))
    Text(state.statusText, color = Color.White, fontSize = 14.sp)
    if (state.isStreaming) Text("${state.fps} fps", color = Color.White, fontSize = 14.sp)
    Spacer(Modifier.weight(1f))
    if (state.capturingAudio) {
      Icon(Icons.Filled.Mic, contentDescription = "Glasses microphone on", tint = Color(0xFFDC2626))
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
    state: UiState,
    onToggle: () -> Unit,
    onQuality: (Quality) -> Unit,
    onToggleAudio: () -> Unit,
    onShare: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
              .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      Quality.entries.forEachIndexed { index, quality ->
        SegmentedButton(
            selected = state.quality == quality,
            onClick = { onQuality(quality) },
            shape = SegmentedButtonDefaults.itemShape(index, Quality.entries.size),
        ) {
          Text(quality.label)
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
          onClick = onToggle,
          modifier = Modifier.weight(1f).height(52.dp),
          colors =
              if (state.wantsStream) ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
              else ButtonDefaults.buttonColors(),
      ) {
        Icon(if (state.wantsStream) Icons.Filled.Stop else Icons.Filled.PlayArrow, null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.wantsStream) "Stop" else "Start live view")
      }
      OutlinedButton(onClick = onToggleAudio, modifier = Modifier.height(52.dp)) {
        Icon(
            if (state.audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription =
                if (state.audioEnabled) "Turn off glasses microphone" else "Turn on glasses microphone",
            tint = if (state.audioEnabled) Color(0xFFDC2626) else Color.White,
        )
      }
      OutlinedButton(onClick = onShare, modifier = Modifier.height(52.dp)) {
        Icon(Icons.Filled.Computer, null)
        Spacer(Modifier.width(8.dp))
        Text("Desktop")
      }
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
