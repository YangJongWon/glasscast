package dev.glasscast

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.POST_NOTIFICATIONS
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import dev.glasscast.ui.GlassCastScreen
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  private val viewModel: GlassCastViewModel by viewModels()

  private val androidPermissions: Array<String> =
      buildList {
            add(BLUETOOTH)
            add(BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(POST_NOTIFICATIONS)
          }
          .toTypedArray()

  private val androidPermissionLauncher =
      registerForActivityResult(RequestMultiplePermissions()) { result ->
        // The DAT SDK needs Bluetooth; notifications are optional (foreground service notice).
        if (result[BLUETOOTH_CONNECT] != false) {
          Wearables.initialize(this)
          viewModel.onSdkReady()
        }
      }

  // Camera permission for the glasses is granted in the Meta AI app.
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  private val wearablesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        permissionContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermissionLauncher.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      GlassCastScreen(
          viewModel = viewModel,
          onConnect = { viewModel.connectGlasses(this) },
          onDisconnect = { viewModel.disconnectGlasses(this) },
          onConfirmCameraPermission = {
            viewModel.confirmCameraPermission(::requestWearablesPermission)
          },
      )
    }
  }

  override fun onStart() {
    super.onStart()
    androidPermissionLauncher.launch(androidPermissions)
  }
}
