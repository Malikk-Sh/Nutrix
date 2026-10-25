package com.nutrix.app

import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/** Restarts the process after data was replaced (restore / delete all), so every cache is fresh. */
object AppRestarter {
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }
}
