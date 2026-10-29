package com.nutrix.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Capture real Compose windows, including a modal sheet's separate dialog window. */
object UiScreenshot {
    fun capture(activity: Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing && it.window?.decorView?.isAttachedToWindow == true }?.window?.decorView?.let { dialog ->
            val location = IntArray(2)
            dialog.getLocationOnScreen(location)
            canvas.save()
            canvas.translate(location[0].toFloat(), location[1].toFloat())
            dialog.draw(canvas)
            canvas.restore()
        }
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
