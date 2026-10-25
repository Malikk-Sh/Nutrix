package com.nutrix.app.feature.food

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import com.nutrix.app.core.ui.icons.RestartAlt
import com.nutrix.app.core.ui.icons.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nutrix.app.R
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.ui.components.NutrixScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@HiltViewModel
class CropViewModel @Inject constructor(private val photos: PhotoStorage) : ViewModel() {
    suspend fun loadPreview(fileName: String, rotation: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = photos.file(fileName)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1400) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        if (rotation % 360 == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    suspend fun crop(fileName: String, rotation: Int, rect: Rect): String =
        photos.crop(fileName, rotation, rect.left, rect.top, rect.right, rect.bottom)
}

private enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

@Composable
fun CropScreen(fileName: String, onDone: (String) -> Unit, onBack: () -> Unit, viewModel: CropViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    var rotation by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(rotation) {
        bitmap = viewModel.loadPreview(fileName, rotation)?.asImageBitmap()
        crop = Rect(0f, 0f, 1f, 1f)
    }

    NutrixScreen(
        title = stringResource(R.string.crop_title),
        onBack = onBack,
        actions = {
            TextButton(
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching { viewModel.crop(fileName, rotation, crop) }
                            .onSuccess(onDone)
                            .onFailure { saving = false }
                    }
                },
                enabled = !saving && bitmap != null,
            ) {
                Icon(Icons.Rounded.Check, null)
                Text(stringResource(R.string.action_done))
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            val image = bitmap
            if (image == null || saving) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                CropArea(image, crop, onCropChange = { crop = it }, modifier = Modifier.padding(bottom = 88.dp))
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                FilledTonalButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Icon(Icons.Rounded.RotateRight, null)
                    Text(" " + stringResource(R.string.crop_rotate))
                }
                FilledTonalButton(onClick = { crop = Rect(0f, 0f, 1f, 1f) }) {
                    Icon(Icons.Rounded.RestartAlt, null)
                    Text(" " + stringResource(R.string.crop_reset))
                }
            }
        }
    }
}

@Composable
private fun CropArea(image: ImageBitmap, crop: Rect, onCropChange: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val handleRadius = with(density) { 28.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = min(boxW / image.width, boxH / image.height)
        val imgW = image.width * scale
        val imgH = image.height * scale
        val imgSize = Size(imgW, imgH)
        val currentCrop by androidx.compose.runtime.rememberUpdatedState(crop)
        Box(Modifier.size(with(density) { imgW.toDp() }, with(density) { imgH.toDp() })) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(imgW, imgH) {
                        var handle = Handle.MOVE
                        detectDragGestures(
                            onDragStart = { pos ->
                                val r = currentCrop.toPx(imgSize)
                                handle = when {
                                    (pos - r.topLeft).getDistance() < handleRadius -> Handle.TOP_LEFT
                                    (pos - r.topRight).getDistance() < handleRadius -> Handle.TOP_RIGHT
                                    (pos - r.bottomLeft).getDistance() < handleRadius -> Handle.BOTTOM_LEFT
                                    (pos - r.bottomRight).getDistance() < handleRadius -> Handle.BOTTOM_RIGHT
                                    else -> Handle.MOVE
                                }
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val dx = drag.x / imgW
                                val dy = drag.y / imgH
                                onCropChange(adjust(currentCrop, handle, dx, dy))
                            },
                        )
                    },
            ) {
                val r = crop.toPx(size)
                val shade = Color.Black.copy(alpha = 0.55f)
                drawRect(shade, Offset.Zero, Size(size.width, r.top))
                drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
                drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
                drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                // Rule-of-thirds guides
                for (i in 1..2) {
                    val x = r.left + r.width * i / 3
                    val y = r.top + r.height * i / 3
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
                listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach {
                    drawCircle(accent, radius = 9.dp.toPx(), center = it)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = it, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

private fun Rect.toPx(size: Size) = Rect(left * size.width, top * size.height, right * size.width, bottom * size.height)

private fun adjust(r: Rect, handle: Handle, dx: Float, dy: Float): Rect {
    val minSize = 0.1f
    return when (handle) {
        Handle.MOVE -> {
            val w = r.width
            val h = r.height
            val left = (r.left + dx).coerceIn(0f, 1f - w)
            val top = (r.top + dy).coerceIn(0f, 1f - h)
            Rect(left, top, left + w, top + h)
        }
        Handle.TOP_LEFT -> Rect((r.left + dx).coerceIn(0f, r.right - minSize), (r.top + dy).coerceIn(0f, r.bottom - minSize), r.right, r.bottom)
        Handle.TOP_RIGHT -> Rect(r.left, (r.top + dy).coerceIn(0f, r.bottom - minSize), (r.right + dx).coerceIn(r.left + minSize, 1f), r.bottom)
        Handle.BOTTOM_LEFT -> Rect((r.left + dx).coerceIn(0f, r.right - minSize), r.top, r.right, (r.bottom + dy).coerceIn(r.top + minSize, 1f))
        Handle.BOTTOM_RIGHT -> Rect(r.left, r.top, (r.right + dx).coerceIn(r.left + minSize, 1f), (r.bottom + dy).coerceIn(r.top + minSize, 1f))
    }
}
