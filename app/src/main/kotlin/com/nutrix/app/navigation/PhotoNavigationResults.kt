package com.nutrix.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry

/**
 * Results belong to the navigation entry, not to a Hilt ViewModel's separate SavedStateHandle.
 * Keep them until the editor has loaded and accepted the photo (including after recreation).
 */
class PhotoNavigationResults internal constructor(private val handle: SavedStateHandle) {
    val cameraPhoto = handle.getStateFlow<String?>(NavResults.CAMERA_PHOTO, null)
    val croppedPhoto = handle.getStateFlow<String?>(NavResults.CROPPED_PHOTO, null)

    fun cameraConsumed(file: String) {
        if (handle.get<String>(NavResults.CAMERA_PHOTO) == file) handle[NavResults.CAMERA_PHOTO] = null
    }

    fun cropConsumed(file: String) {
        if (handle.get<String>(NavResults.CROPPED_PHOTO) == file) handle[NavResults.CROPPED_PHOTO] = null
    }
}

@Composable
fun rememberPhotoNavigationResults(entry: NavBackStackEntry): PhotoNavigationResults =
    remember(entry) { PhotoNavigationResults(entry.savedStateHandle) }
