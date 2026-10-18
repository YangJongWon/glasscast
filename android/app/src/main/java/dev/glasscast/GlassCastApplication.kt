package dev.glasscast

import android.app.Application
import android.util.Log
import dev.glasscast.server.MjpegServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlassCastApplication : Application() {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Lives as long as the process; StreamService keeps the process alive while streaming. */
  lateinit var server: MjpegServer
    private set

  override fun onCreate() {
    super.onCreate()
    val viewerHtml =
        runCatching { assets.open("viewer.html").use { it.readBytes() } }
            .getOrElse {
              Log.w("GlassCast", "viewer.html missing from assets", it)
              "<h1>GlassCast</h1><p><a href=\"stream\">Open the raw stream</a></p>".toByteArray()
            }
    server = MjpegServer(this, viewerHtml, appScope)
    server.start()
  }
}
