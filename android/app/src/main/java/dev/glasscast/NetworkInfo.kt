package dev.glasscast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.glasscast.server.MjpegServer
import java.net.Inet4Address

object NetworkInfo {
  /** The phone's IPv4 address on Wi-Fi (or Ethernet), or null when not on a local network. */
  fun localIPv4Address(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return manager.allNetworks
        .filter { network ->
          val caps = manager.getNetworkCapabilities(network) ?: return@filter false
          caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
              caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        .firstNotNullOfOrNull { network ->
          manager.getLinkProperties(network)?.linkAddresses
              ?.map { it.address }
              ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
              ?.hostAddress
        }
  }

  /** The URL a computer on the same network opens to watch the stream. */
  fun viewerUrl(context: Context): String? =
      localIPv4Address(context)?.let { "http://$it:${MjpegServer.PORT}" }
}
