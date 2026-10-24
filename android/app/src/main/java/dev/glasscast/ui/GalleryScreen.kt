package dev.glasscast.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.glasscast.photos.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Photos taken with the glasses, newest first. Tap one to view, share or delete it. */
@Composable
fun GalleryScreen(store: PhotoStore, onClose: () -> Unit) {
  val photos by store.photos.collectAsStateWithLifecycle()
  var open by remember { mutableStateOf<PhotoStore.Photo?>(null) }

  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
        }
        Text("Photos", color = Color.White, fontSize = 18.sp)
      }
      if (photos.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(
              "No photos yet.\nStart live view, then tap the shutter to take a photo with your glasses, or switch to VIDEO to record.",
              color = Color.White.copy(alpha = 0.7f),
              textAlign = TextAlign.Center,
          )
        }
      } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(photos, key = { it.uri }) { photo ->
            Thumbnail(store, photo, Modifier.clickable { open = photo })
          }
        }
      }
    }
  }

  open?.let { photo -> PhotoDetail(store, photo, onClose = { open = null }) }
}

@Composable
private fun Thumbnail(store: PhotoStore, photo: PhotoStore.Photo, modifier: Modifier) {
  val bitmap by
      produceState<Bitmap?>(null, photo) { value = withContext(Dispatchers.IO) { store.thumbnail(photo, 300) } }
  Box(modifier.aspectRatio(1f).background(Color.DarkGray)) {
    bitmap?.let {
      Image(
          bitmap = it.asImageBitmap(),
          contentDescription = photo.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    }
    if (photo.isVideo) {
      Icon(
          Icons.Filled.Videocam,
          contentDescription = "Video",
          tint = Color.White,
          modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp),
      )
    }
  }
}

@Composable
private fun PhotoDetail(store: PhotoStore, photo: PhotoStore.Photo, onClose: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(photo) {
    bitmap =
        withContext(Dispatchers.IO) {
          if (photo.isVideo) {
            store.thumbnail(photo, 1080)
          } else {
            runCatching {
                  ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo.uri))
                }
                .getOrNull()
          }
        }
  }

  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(photo.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        IconButton(
            onClick = {
              val send =
                  Intent(Intent.ACTION_SEND)
                      .setType(if (photo.isVideo) "video/mp4" else "image/jpeg")
                      .putExtra(Intent.EXTRA_STREAM, photo.uri)
                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              context.startActivity(Intent.createChooser(send, null))
            }) {
              Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
            }
        IconButton(
            onClick = {
              scope.launch {
                store.delete(photo)
                onClose()
              }
            }) {
              Icon(Icons.Filled.Delete, contentDescription = "Delete photo", tint = Color.White)
            }
      }
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
          Image(
              bitmap = image.asImageBitmap(),
              contentDescription = photo.name,
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize(),
          )
        } else {
          CircularProgressIndicator(color = Color.White)
        }
        if (photo.isVideo) {
          // Plays in the phone's video player.
          IconButton(
              onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(photo.uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
              },
              modifier = Modifier.size(88.dp),
          ) {
            Icon(Icons.Filled.PlayCircle, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(80.dp))
          }
        }
      }
    }
  }
}
