package com.nutrix.app.backup

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.database.NutrixDatabase
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestoreRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun interruptedSwap(committed: Boolean) {
        val db = NutrixDatabase.build(context)
        val sqlite = db.openHelper.writableDatabase
        val root = RestoreRecovery.directory(context).apply { mkdirs() }
        File(root, "settings.preferences_pb").writeText("original preferences")
        val current = RestoreRecovery.photos(context).apply { mkdirs() }
        File(current, "old.jpg").writeText("original photo")
        val incoming = File(root, "new_photos").apply { mkdirs() }
        File(incoming, "new.jpg").writeText("restored photo")
        RestoreRecovery.writeState(root, RestoreRecovery.State("transaction-id", true, true))
        RestoreRecovery.move(current, File(root, "old_photos"))
        RestoreRecovery.move(incoming, current)
        context.preferencesDataStoreFile("settings").apply { parentFile!!.mkdirs(); writeText("restored preferences") }
        if (committed) sqlite.execSQL("INSERT INTO drafts(kind,payload,updatedAt) VALUES(?,?,0)",
            arrayOf(RestoreRecovery.MARKER, "transaction-id"))
        db.close()
    }

    @Test fun interruptedBeforeCommitRestoresOriginalFilesAndIsIdempotent() {
        interruptedSwap(false)
        RestoreRecovery.recover(context)
        RestoreRecovery.recover(context)
        assertThat(File(RestoreRecovery.photos(context), "old.jpg").readText()).isEqualTo("original photo")
        assertThat(File(RestoreRecovery.photos(context), "new.jpg").exists()).isFalse()
        assertThat(context.preferencesDataStoreFile("settings").readText()).isEqualTo("original preferences")
        assertThat(RestoreRecovery.directory(context).exists()).isFalse()
    }

    @Test fun interruptedAfterCommitKeepsRestoredFilesAndCleansMarker() {
        interruptedSwap(true)
        RestoreRecovery.recover(context)
        assertThat(File(RestoreRecovery.photos(context), "new.jpg").readText()).isEqualTo("restored photo")
        assertThat(context.preferencesDataStoreFile("settings").readText()).isEqualTo("restored preferences")
        val db = NutrixDatabase.build(context)
        db.openHelper.readableDatabase.query("SELECT * FROM drafts WHERE kind = ?", arrayOf(RestoreRecovery.MARKER)).use {
            assertThat(it.count).isEqualTo(0)
        }
        db.close()
        assertThat(RestoreRecovery.directory(context).exists()).isFalse()
    }
}
