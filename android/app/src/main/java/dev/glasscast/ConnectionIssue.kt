package dev.glasscast

import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.PermissionError

/**
 * A problem with the glasses, in words a user can act on: what happened and what to do.
 * Shown as a banner over the live view instead of the SDK's raw error text.
 */
data class ConnectionIssue(
    val kind: Kind,
    val title: String,
    val message: String,
    /** Whether "Try again" makes sense. */
    val canRetry: Boolean = true,
) {
  enum class Kind {
    /** The glasses aren't reachable; usually fixed by waking or reconnecting them. */
    CONNECTION,
    /** The glasses stopped on purpose (heat, battery, folded); wait or fix, then retry. */
    DEVICE,
    /** Something GlassCast or the user needs to change (update, permission). */
    SETUP,
  }

  companion object {
    val NOT_CONNECTED =
        ConnectionIssue(
            Kind.CONNECTION,
            "Glasses not connected",
            "Unfold your glasses, put them on and keep them near your phone. If they still don't connect, open the Meta AI app once to wake the connection.",
        )

    val LOST =
        ConnectionIssue(
            Kind.CONNECTION,
            "Connection to your glasses was lost",
            "The glasses may have been taken off, folded, or moved out of Bluetooth range. Put them back on and tap Try again.",
        )

    private val NO_GLASSES =
        ConnectionIssue(
            Kind.CONNECTION,
            "No glasses found",
            "Make sure your glasses are turned on, paired in the Meta AI app, and near your phone.",
        )

    private val HOT =
        ConnectionIssue(
            Kind.DEVICE,
            "Your glasses are too warm",
            "The camera paused to cool down. Take the glasses off for a few minutes, then try again. A lower quality setting runs cooler.",
        )

    private val BATTERY =
        ConnectionIssue(Kind.DEVICE, "Glasses battery is low", "Charge your glasses in their case, then try again.")

    private val POWER =
        ConnectionIssue(
            Kind.DEVICE,
            "Glasses need more power",
            "The glasses can't power the camera right now, usually because the battery is low. Charge them, then try again.",
        )

    private val META_AI_MISSING =
        ConnectionIssue(
            Kind.SETUP,
            "Meta AI app not installed",
            "GlassCast connects to your glasses through the Meta AI app. Install it from Google Play, pair your glasses, then try again.",
            canRetry = false,
        )

    private fun outOfStep(detail: String) =
        ConnectionIssue(
            Kind.CONNECTION,
            "Couldn't start live view",
            "The connection to your glasses got out of step. Tap Try again. ($detail)",
        )

    /** Any DAT error from the session, stream or permission APIs. */
    fun from(error: DatError): ConnectionIssue =
        when (error) {
          is DeviceSessionError -> from(error)
          is StreamError -> from(error)
          is PermissionError -> from(error)
          else -> ConnectionIssue(Kind.CONNECTION, "Live view stopped", error.description)
        }

    fun from(error: DeviceSessionError): ConnectionIssue =
        when (error) {
          DeviceSessionError.NO_ELIGIBLE_DEVICE -> NO_GLASSES
          DeviceSessionError.DEVICE_DISCONNECTED,
          DeviceSessionError.SESSION_ENDED_BY_DEVICE -> LOST
          DeviceSessionError.THERMAL_CRITICAL,
          DeviceSessionError.THERMAL_EMERGENCY -> HOT
          DeviceSessionError.BATTERY_CRITICAL -> BATTERY
          DeviceSessionError.PEAK_POWER_SHUTDOWN -> POWER
          DeviceSessionError.CAPABILITY_DENIED ->
              ConnectionIssue(
                  Kind.SETUP,
                  "Camera access is off",
                  "Allow GlassCast to use the glasses camera in the Meta AI app, then try again.",
              )
          DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
              ConnectionIssue(
                  Kind.SETUP,
                  "Glasses need an update",
                  "Open the Meta AI app and install the latest update for your glasses, then try again.",
                  canRetry = false,
              )
          DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
              ConnectionIssue(
                  Kind.SETUP,
                  "GlassCast needs an update",
                  "This version of GlassCast is too old for your glasses. Install the latest version.",
                  canRetry = false,
              )
          DeviceSessionError.DWA_UNAVAILABLE ->
              ConnectionIssue(
                  Kind.CONNECTION,
                  "Meta AI app isn't connected to your glasses",
                  "Open the Meta AI app, check that it shows your glasses as connected, then come back and try again.",
              )
          else -> outOfStep(error.description)
        }

    fun from(error: StreamError): ConnectionIssue =
        when (error) {
          StreamError.TIMEOUT ->
              ConnectionIssue(
                  Kind.CONNECTION,
                  "The glasses didn't respond",
                  "Keep your phone close to the glasses and try again. A lower quality setting needs less Bluetooth bandwidth.",
              )
          StreamError.STREAM_ERROR,
          StreamError.CRITICAL_STREAM_ERROR ->
              ConnectionIssue(
                  Kind.CONNECTION,
                  "Video from the glasses was interrupted",
                  "The Bluetooth link may be weak. Move your phone closer or choose 360p, then try again.",
              )
          StreamError.HINGE_CLOSED ->
              ConnectionIssue(Kind.DEVICE, "Your glasses are folded", "Unfold your glasses and put them on to continue.")
          StreamError.PERMISSIONS_DENIED ->
              ConnectionIssue(
                  Kind.SETUP,
                  "Camera access is off",
                  "Allow GlassCast to use the glasses camera in the Meta AI app, then try again.",
              )
          StreamError.THERMAL_HOT -> HOT
          StreamError.BATTERY_LOW -> BATTERY
          StreamError.PEAK_POWER_LIMIT -> POWER
        }

    fun from(error: PermissionError): ConnectionIssue =
        when (error) {
          PermissionError.NO_DEVICE -> NO_GLASSES
          PermissionError.NO_DEVICE_WITH_CONNECTION,
          PermissionError.CONNECTION_ERROR -> NOT_CONNECTED
          PermissionError.META_AI_NOT_INSTALLED -> META_AI_MISSING
          PermissionError.REQUEST_IN_PROGRESS ->
              ConnectionIssue(
                  Kind.SETUP,
                  "Waiting for your answer in Meta AI",
                  "Finish the camera permission request in the Meta AI app, then come back.",
              )
          PermissionError.REQUEST_TIMEOUT ->
              ConnectionIssue(
                  Kind.SETUP,
                  "Camera permission wasn't confirmed",
                  "The Meta AI app didn't answer in time. Tap Try again and approve camera access there.",
              )
          PermissionError.INTERNAL_ERROR ->
              ConnectionIssue(Kind.SETUP, "Couldn't ask for camera access", error.description)
        }
  }
}
