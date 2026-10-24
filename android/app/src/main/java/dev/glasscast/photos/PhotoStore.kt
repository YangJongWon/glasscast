package dev.glasscast.photos

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Photos taken with the glasses camera, saved to `Pictures/GlassCast` through MediaStore so
 * they also show up in the phone's gallery app. No storage permission is needed: since
 * Android 10 an app can write to, list and delete the media it created itself.
 */
class PhotoStore(private val context: Context) {
  companion object {
    private const val RELATIVE_PATH = "Pictures/GlassCast/"
  }

  data class Photo(val uri: Uri, val name: String)

  private val _photos = MutableStateFlow<List<Photo>>(emptyList())
  /** Newest first. */
  val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

  private val _latestThumbnail = MutableStateFlow<Bitmap?>(null)
  val latestThumbnail: StateFlow<Bitmap?> = _latestThumbnail.asStateFlow()

  private val resolver get() = context.contentResolver
  private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

  suspend fun reload() =
      withContext(Dispatchers.IO) {
        val found = mutableListOf<Photo>()
        resolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
                arrayOf(RELATIVE_PATH),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
            ?.use { cursor ->
              val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
              val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
              while (cursor.moveToNext()) {
                val uri =
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id))
                found += Photo(uri, cursor.getString(name))
              }
            }
        _photos.value = found
        _latestThumbnail.value = found.firstOrNull()?.let(::thumbnail)
      }

  /** Saves a JPEG from the glasses. */
  suspend fun add(jpeg: ByteArray): Photo =
      withContext(Dispatchers.IO) {
        val name = "glasscast-${nameFormat.format(Date())}.jpg"
        val values =
            ContentValues().apply {
              put(MediaStore.Images.Media.DISPLAY_NAME, name)
              put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
              put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
              put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create the photo in Pictures/GlassCast.")
        try {
          resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: error("Couldn't write the photo.")
          resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
          resolver.delete(uri, null, null)
          throw e
        }
        val photo = Photo(uri, name)
        _photos.update { listOf(photo) + it }
        _latestThumbnail.value = thumbnail(photo)
        photo
      }

  suspend fun delete(photo: Photo) =
      withContext(Dispatchers.IO) {
        // Fails for photos from before a reinstall, which this install no longer owns.
        runCatching { resolver.delete(photo.uri, null, null) }
        _photos.update { list -> list.filter { it != photo } }
        _latestThumbnail.value = _photos.value.firstOrNull()?.let(::thumbnail)
      }

  fun thumbnail(photo: Photo, size: Int = 240): Bitmap? =
      runCatching { resolver.loadThumbnail(photo.uri, Size(size, size), null) }.getOrNull()
}
