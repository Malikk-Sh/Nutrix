package com.nutrix.app.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.backup.*
import com.nutrix.app.core.datastore.SettingsRepository
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.data.repository.DataChangeNotifier
import com.nutrix.app.data.repository.MealRepository
import com.nutrix.app.database.entity.MealEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NutritionMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun versionOne(file: File) = olderVersion(file, 1)

    /** A database file exactly as an older version of the app created it, with one meal and one favorite. */
    private fun olderVersion(file: File, version: Int) {
        file.parentFile!!.mkdirs()
        file.delete()
        val schema = Json.parseToJsonElement(File("schemas/com.nutrix.app.database.NutrixDatabase/$version.json").readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val table = element.jsonObject
                val name = table.getValue("tableName").jsonPrimitive.content
                db.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                table["indices"]?.jsonArray.orEmpty().forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO meals(id,epochMillis,zoneId,localDate,mealType,title,items,feelings,createdAt,updatedAt,portionGrams,caloriesEstimate) VALUES (1,0,'UTC',0,'LUNCH','Старый обед','[\"Суп\",\"Хлеб\"]','',1,1,450,620)")
            db.execSQL("INSERT INTO favorite_meals(id,title,items,useCount,lastUsedAt,createdAt) VALUES (1,'Старый шаблон','[]',0,0,1)")
            db.version = version
        }
    }

    @Test fun `upgrade preserves version one meals and favorites and stores nutrition`() = runBlocking {
        context.deleteDatabase(NutrixDatabase.NAME)
        versionOne(context.getDatabasePath(NutrixDatabase.NAME))
        val db = NutrixDatabase.build(context)
        try {
            val repo = MealRepository(db, db.mealDao(), PhotoStorage(context), DataChangeNotifier())
            val meal = repo.get(1)!!.meal
            assertThat(meal.title).isEqualTo("Старый обед")
            assertThat(meal.nutrition).isNull()
            assertThat(repo.getFavorite(1)!!.title).isEqualTo("Старый шаблон")
            val nutrition = MealNutrition(12.5, 6.0, 20.0, "gemini-3.1-flash-lite", 100, "Масло неизвестно")
            repo.save(meal.copy(nutrition = nutrition), emptyList(), saveAsFavorite = true)
            assertThat(repo.get(1)!!.meal.nutrition).isEqualTo(nutrition)
            assertThat(repo.observeFavorites().first().first { it.title == "Старый обед" }.nutrition).isEqualTo(nutrition)
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(3)
        } finally { db.close() }
    }

    @Test fun `version one backup validates without changing checksum and restores into version two`() = runBlocking {
        context.deleteDatabase(NutrixDatabase.NAME)
        val old = File(context.cacheDir, "legacy-diary.db")
        versionOne(old)
        val oldBytes = old.readBytes()
        val settingsBytes = "{}".toByteArray()
        val settingsFile = File(context.cacheDir, "legacy-settings.json").apply { writeBytes(settingsBytes) }
        val manifest = BackupManifest(appVersion = "1.0.0", dbVersion = 1, createdAt = 1, encrypted = false, photoCount = 0,
            files = listOf(BackupFileEntry(BackupManager.DB_ENTRY, BackupManager.sha256(old), old.length()),
                BackupFileEntry(BackupManager.SETTINGS_ENTRY, BackupManager.sha256(settingsFile), settingsFile.length())))
        val archive = File(context.cacheDir, "legacy-backup.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf(BackupManager.DB_ENTRY to oldBytes, BackupManager.SETTINGS_ENTRY to settingsBytes,
                BackupManager.MANIFEST_ENTRY to Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val db = NutrixDatabase.build(context)
        try {
            val settings = SettingsRepository(context).also { it.clearAll() }
            val backup = BackupManager(context, db, settings, PhotoStorage(context))
            val result = backup.check(archive, null)
            assertThat(result).isInstanceOf(RestoreCheck.Ok::class.java)
            val ok = result as RestoreCheck.Ok
            assertThat(File(ok.dir, BackupManager.DB_ENTRY).readBytes()).isEqualTo(oldBytes)
            backup.restore(ok)
            val meal = db.mealDao().get(1)!!.meal
            assertThat(meal.title).isEqualTo("Старый обед")
            assertThat(meal.nutrition).isNull()
            assertThat(db.mealDao().getFavorite(1)!!.title).isEqualTo("Старый шаблон")
            // A subsequent backup uses the new schema and round-trips the optional nutrition.
            val repo = MealRepository(db, db.mealDao(), PhotoStorage(context), DataChangeNotifier())
            repo.save(meal.copy(nutrition = MealNutrition(proteinGrams = 12.5)), emptyList())
            val current = File(context.cacheDir, "nutrition-backup.zip")
            current.outputStream().use { backup.createBackup(it, null) }
            repo.save(meal.copy(title = "Изменено"), emptyList())
            backup.restore(backup.check(current, null) as RestoreCheck.Ok)
            assertThat(repo.get(1)!!.meal.nutrition?.proteinGrams).isEqualTo(12.5)
        } finally { db.close() }
    }

    @Test fun `version two meals keep their whole-meal estimate and gain no invented dishes`() = runBlocking {
        context.deleteDatabase(NutrixDatabase.NAME)
        olderVersion(context.getDatabasePath(NutrixDatabase.NAME), 2)
        val db = NutrixDatabase.build(context)
        try {
            val repo = MealRepository(db, db.mealDao(), PhotoStorage(context), DataChangeNotifier())
            val meal = repo.get(1)!!.meal
            // The old total stays a whole-meal estimate: nothing is split between "Суп" and "Хлеб".
            assertThat(meal.dishes).isNull()
            assertThat(meal.caloriesEstimate).isEqualTo(620)
            assertThat(meal.portionGrams).isEqualTo(450)
            assertThat(meal.items).containsExactly("Суп", "Хлеб").inOrder()
            assertThat(repo.getFavorite(1)!!.dishes).isNull()
            // Dishes saved after the upgrade round-trip and drive the meal's totals.
            repo.save(meal.copy(dishes = listOf(com.nutrix.app.core.model.MealDish(name = "Суп", kcal = 300.0))), emptyList())
            val upgraded = repo.get(1)!!.meal
            assertThat(upgraded.dishes!!.single().name).isEqualTo("Суп")
            assertThat(upgraded.caloriesEstimate).isEqualTo(300)
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(3)
        } finally { db.close() }
    }
}
