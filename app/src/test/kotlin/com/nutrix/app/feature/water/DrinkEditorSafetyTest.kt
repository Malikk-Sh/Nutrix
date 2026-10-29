package com.nutrix.app.feature.water

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.DrinkRepository
import com.nutrix.app.data.repository.UndoManager
import com.nutrix.app.data.repository.UserMessages
import com.nutrix.app.database.NutrixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.nutrix.app.core.time.TimeUtils
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrinkEditorSafetyTest {
    private lateinit var db: NutrixDatabase
    private lateinit var drinks: DrinkRepository
    private lateinit var photos: PhotoStorage
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(NutrixDatabase.NAME)
        db = NutrixDatabase.build(context)
        photos = PhotoStorage(context).also { it.deleteAll() }
        drinks = DrinkRepository(db.drinkDao(), photos, DataChangeNotifier())
    }

    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun editor(): Pair<DrinkEditorViewModel, ViewModelStore> {
        val store = ViewModelStore().also(stores::add)
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                DrinkEditorViewModel(SavedStateHandle(), drinks, photos, UndoManager(), UserMessages()) as T
        })[DrinkEditorViewModel::class.java]
        return vm to store
    }

    private fun photo(name: String): String = name.also { photos.file(it).writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun `double tap saves a new drink exactly once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        vm.update { it.copy(volumeText = "300") }
        vm.save()
        vm.save()
        vm.closed.first { it }
        vm.save()
        testScheduler.advanceUntilIdle()
        assertThat(db.drinkDao().count()).isEqualTo(1)
    }

    @Test fun `leaving during a save keeps the photo and the drink`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, store) = editor()
        val file = photo("drink-photo.jpg")
        assertThat(vm.consumeCamera(file)).isTrue()
        vm.save()
        // Back is pressed while the write is still queued, then the screen goes away.
        vm.cancel()
        store.clear()
        testScheduler.advanceUntilIdle()
        // The write continues on Room's thread after the view model is gone.
        val saved = withContext(Dispatchers.IO) { drinks.observeDay(TimeUtils.todayEpochDay()).first { it.isNotEmpty() } }.single()
        assertThat(saved.photoFile).isEqualTo(file)
        assertThat(photos.exists(file)).isTrue()
    }

    @Test fun `cancel without saving removes only the new photo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = editor()
        val file = photo("unsaved.jpg")
        vm.consumeCamera(file)
        vm.cancel()
        testScheduler.advanceUntilIdle()
        assertThat(photos.exists(file)).isFalse()
        assertThat(db.drinkDao().count()).isEqualTo(0)
    }
}
