package com.nutrix.app.feature.activity

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.time.TimeUtils
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.HealthRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.NutrixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SleepEditorSafetyTest {
    @Test fun `double tap saves one manual nap and editing updates it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NutrixDatabase.NAME)
        val db = NutrixDatabase.build(context)
        val stores = mutableListOf<ViewModelStore>()
        try {
            val settings = SettingsRepository(context).also { it.clearAll() }
            val health = HealthRepository(db.healthDao(), settings, DataChangeNotifier())
            fun editor(id: Long = 0): SleepEditorViewModel {
                val store = ViewModelStore().also(stores::add)
                return ViewModelProvider(store, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        SleepEditorViewModel(SavedStateHandle(mapOf("sleepId" to id)), health, UndoManager(), UserMessages()) as T
                })[SleepEditorViewModel::class.java]
            }
            val vm = editor()
            vm.update { it.copy(startDate = LocalDate.now(), endDate = LocalDate.now(), startTime = LocalTime.of(14, 0), endTime = LocalTime.of(15, 0)) }
            vm.save()
            vm.save()
            vm.closed.first { it }
            val saved = health.getSleepRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).single()
            assertThat(saved.source).isEqualTo(DataSource.MANUAL)
            assertThat(saved.durationMinutes).isEqualTo(60)
            val edit = editor(saved.id)
            edit.loaded.first { it }
            edit.update { it.copy(endTime = LocalTime.of(16, 0)) }
            edit.save()
            edit.closed.first { it }
            val updated = health.getSleepRange(TimeUtils.todayEpochDay(), TimeUtils.todayEpochDay()).single()
            assertThat(updated.id).isEqualTo(saved.id)
            assertThat(updated.durationMinutes).isEqualTo(120)
            assertThat(updated.createdAt).isEqualTo(saved.createdAt)
        } finally {
            stores.forEach { it.clear() }
            Dispatchers.resetMain()
            db.close()
        }
    }
}
