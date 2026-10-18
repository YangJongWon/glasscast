package dev.glasscast.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.glasscast.MainActivity
import dev.glasscast.NetworkInfo
import dev.glasscast.R

/**
 * Foreground service that runs while the glasses are streaming, so the process, CPU and
 * Wi-Fi stay up with the screen off and desktop viewers keep receiving frames.
 */
class StreamService : Service() {
  companion object {
    private const val CHANNEL_ID = "live_view"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) {
      context.startForegroundService(Intent(context, StreamService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, StreamService::class.java))
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null
  private var wifiLock: WifiManager.WifiLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(
        NOTIFICATION_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    acquireLocks()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wifiLock?.takeIf { it.isHeld }?.release()
    super.onDestroy()
  }

  private fun acquireLocks() {
    if (wakeLock == null) {
      wakeLock =
          getSystemService(PowerManager::class.java)
              ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassCast:stream")
              ?.apply { acquire() }
    }
    if (wifiLock == null) {
      @Suppress("DEPRECATION")
      wifiLock =
          getSystemService(WifiManager::class.java)
              ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlassCast:stream")
              ?.apply { acquire() }
    }
  }

  private fun buildNotification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ))
    val openApp =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    val url = NetworkInfo.viewerUrl(this)
    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(
            if (url != null) getString(R.string.notification_text, url)
            else getString(R.string.notification_text_no_wifi))
        .setContentIntent(openApp)
        .setOngoing(true)
        .build()
  }
}
