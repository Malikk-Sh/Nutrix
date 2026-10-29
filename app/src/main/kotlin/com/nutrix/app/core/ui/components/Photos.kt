package com.nutrix.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.nutrix.app.core.ui.icons.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nutrix.app.R
import com.nutrix.app.core.ui.icons.NoPhotography
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nutrix.app.core.photo.PhotoStorage
import java.io.File

/** Resolves a stored photo file name to its private file. */
@Composable
fun photoFile(fileName: String): File = File(File(LocalContext.current.filesDir, PhotoStorage.DIR), fileName)

@Composable
fun PhotoThumb(
    fileName: String?,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    shape: Shape = MaterialTheme.shapes.small,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    var failed by remember(fileName) { mutableStateOf(false) }
    Box(
        modifier
            .testTag("photo:$fileName")
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (fileName != null) {
            AsyncImage(
                model = photoFile(fileName),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
                onError = { failed = true },
                onSuccess = { failed = false },
            )
            if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.NoPhotography, stringResource(R.string.photo_unavailable), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size / 3))
            }
        } else {
            Icon(Icons.Rounded.Restaurant, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size / 2.4f))
        }
    }
}

/** A full-width photo for the meal editor; the gallery still uses square thumbnails. */
@Composable
fun PhotoHero(fileName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var failed by remember(fileName) { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(176.dp).testTag("photo:$fileName")
        .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AsyncImage(model = photoFile(fileName), contentDescription = stringResource(R.string.food_photo_actions),
            contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            onError = { failed = true }, onSuccess = { failed = false })
        if (failed) Icon(Icons.Rounded.NoPhotography, stringResource(R.string.photo_unavailable), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.food_photo_actions), style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .clip(MaterialTheme.shapes.small).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.65f)).padding(10.dp))
    }
}
