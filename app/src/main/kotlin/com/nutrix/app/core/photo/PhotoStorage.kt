package com.nutrix.app.core.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos live in the app's private files directory (not visible to other apps or the gallery).
 * The database stores file names only; files are resolved through [file].
 */
@Singleton
class PhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val directory: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(fileName: String): File = File(directory, fileName)

    fun exists(fileName: String?): Boolean = fileName != null && file(fileName).exists()

    /** Temporary file for CameraX capture, in the private cache. */
    fun newCaptureFile(): File = File(File(context.cacheDir, "capture").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")

    suspend fun importUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val bitmap = decodeSampled({ context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri") }, MAX_SIDE)
        saveBitmap(rotate(bitmap, rotation))
    }

    suspend fun importFile(source: File, deleteSource: Boolean = true): String = withContext(Dispatchers.IO) {
        val rotation = source.inputStream().use { exifRotation(it) }
        val bitmap = decodeSampled({ source.inputStream() }, MAX_SIDE)
        val name = saveBitmap(rotate(bitmap, rotation))
        if (deleteSource) source.delete()
        name
    }

    /** Copies a stored photo (e.g. when creating a favorite from a meal). */
    suspend fun duplicate(fileName: String): String? = withContext(Dispatchers.IO) {
        val src = file(fileName)
        if (!src.exists()) return@withContext null
        val name = "${UUID.randomUUID()}.jpg"
        src.copyTo(file(name), overwrite = true)
        name
    }

    /**
     * Crops a stored photo. [left]..[bottom] are fractions (0..1) of the image after applying
     * [rotationDegrees]. Produces a new file and returns its name; the original is kept until
     * no record references it (see [deleteUnreferenced]).
     */
    suspend fun crop(fileName: String, rotationDegrees: Int, left: Float, top: Float, right: Float, bottom: Float): String =
        withContext(Dispatchers.IO) {
            val src = file(fileName)
            val bitmap = rotate(decodeSampled({ src.inputStream() }, MAX_SIDE), rotationDegrees)
            val x = (left.coerceIn(0f, 1f) * bitmap.width).roundToInt()
            val y = (top.coerceIn(0f, 1f) * bitmap.height).roundToInt()
            val w = ((right - left).coerceIn(0.01f, 1f) * bitmap.width).roundToInt().coerceAtMost(bitmap.width - x).coerceAtLeast(1)
            val h = ((bottom - top).coerceIn(0.01f, 1f) * bitmap.height).roundToInt().coerceAtMost(bitmap.height - y).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
            saveBitmap(cropped)
        }

    fun delete(fileName: String?) {
        if (fileName.isNullOrBlank()) return
        runCatching { file(fileName).delete() }
    }

    /** Removes files that no record references and that are older than [minAgeMillis]. */
    suspend fun deleteUnreferenced(referenced: Set<String>, draftPayloads: List<String>, minAgeMillis: Long = 6 * 60 * 60 * 1000L): Int =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var removed = 0
            directory.listFiles()?.forEach { f ->
                val inDraft = draftPayloads.any { it.contains(f.name) }
                if (f.name !in referenced && !inDraft && now - f.lastModified() > minAgeMillis) {
                    if (f.delete()) removed++
                }
            }
            File(context.cacheDir, "capture").listFiles()?.forEach { f ->
                if (now - f.lastModified() > minAgeMillis) f.delete()
            }
            removed
        }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val name = "${UUID.randomUUID()}.jpg"
        file(name).outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        return name
    }

    private fun exifRotation(stream: InputStream): Int = runCatching {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun decodeSampled(open: () -> InputStream, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Unsupported image")
        val side = max(decoded.width, decoded.height)
        if (side <= maxSide) return decoded
        val scale = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        const val DIR = "photos"
        private const val MAX_SIDE = 2048
        private const val JPEG_QUALITY = 85
    }
}
