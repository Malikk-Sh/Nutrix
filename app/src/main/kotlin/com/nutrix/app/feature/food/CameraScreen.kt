package com.nutrix.app.feature.food

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nutrix.app.R
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.ui.components.LocalExternalTransition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(private val photos: PhotoStorage) : ViewModel() {
    fun newFile(): File = photos.newCaptureFile()
    suspend fun import(file: File): String = photos.importFile(file)
}

@Composable
fun CameraScreen(onCaptured: (String) -> Unit, onBack: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraContent(viewModel, onCaptured)
        } else {
            PermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                showSettings = asked,
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
        }
    }
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, showSettings: Boolean) {
    val context = LocalContext.current
    val external = LocalExternalTransition.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.NoPhotography, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.camera_permission_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) { Text(stringResource(R.string.camera_permission_grant)) }
        if (showSettings) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                external()
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text(stringResource(R.string.camera_permission_settings), color = Color.White) }
        }
    }
}

@Composable
private fun CameraContent(viewModel: CameraViewModel, onCaptured: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashMode by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    imageCapture.flashMode = flashMode

    DisposableEffect(lensFacing) {
        var provider: ProcessCameraProvider? = null
        val job = scope.launch {
            runCatching {
                val p = ProcessCameraProvider.awaitInstance(context)
                provider = p
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                if (!p.hasCamera(selector)) {
                    error = R.string.camera_unavailable
                    return@launch
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                error = null
            }.onFailure { error = R.string.camera_unavailable }
        }
        onDispose {
            job.cancel()
            provider?.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        error?.let {
            Text(
                stringResource(it),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
            }) {
                Icon(
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Rounded.FlashOn
                        ImageCapture.FLASH_MODE_OFF -> Icons.Rounded.FlashOff
                        else -> Icons.Rounded.FlashAuto
                    },
                    stringResource(R.string.camera_flash),
                    tint = Color.White,
                )
            }
            val captureLabel = stringResource(R.string.camera_capture)
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (busy) Color.Gray else Color.White)
                    .semantics { contentDescription = captureLabel }
                    .then(
                        if (!busy) Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            busy = true
                            val file = viewModel.newFile()
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            runCatching { viewModel.import(file) }
                                                .onSuccess(onCaptured)
                                                .onFailure { busy = false; error = R.string.camera_error }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        error = R.string.camera_error
                                    }
                                },
                            )
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            }) {
                Icon(Icons.Rounded.FlipCameraAndroid, stringResource(R.string.camera_flip), tint = Color.White)
            }
        }
    }
}
