package com.nutrix.app.feature.reports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nutrix.app.core.datastore.UserSettings
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealWithPhotos
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.AnalyticsCalculator
import com.nutrix.app.domain.AnalyticsInput
import com.nutrix.app.domain.DailyActivity
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()

    private fun sample(): ReportData {
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 10)
        val meals = (0 until 10).map { i ->
            val rt = RecordTime.of(from.plusDays(i.toLong()), LocalTime.of(13, 0), zone)
            MealWithPhotos(
                MealEntity(
                    id = i + 1L, epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, mealType = MealType.LUNCH,
                    title = "Суп, \"домашний\"", items = listOf("Картофель", "Морковь"), appetite = i % 6, satiety = Satiety.QUICK,
                    feelings = listOf(AfterMealFeeling.NAUSEA), caloriesEstimate = 250, note = "строка 1\nстрока 2",
                ),
                emptyList(),
            )
        }
        val drinks = (0 until 10).map { i ->
            val rt = RecordTime.of(from.plusDays(i.toLong()), LocalTime.of(10, 0), zone)
            DrinkEntity(id = i + 1L, epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, drinkType = DrinkType.TEA, volumeMl = 300)
        }
        val weights = (0 until 10).map { i ->
            val rt = RecordTime.of(from.plusDays(i.toLong()), LocalTime.of(8, 0), zone)
            WeightEntity(id = i + 1L, epochMillis = rt.epochMillis, zoneId = rt.zoneId, localDate = rt.epochDay, weightKg = 55.0 - i * 0.1)
        }
        val symptoms = listOf(SymptomEntity(id = 1, epochMillis = meals[2].meal.epochMillis + 1_800_000, zoneId = zone.id, localDate = meals[2].meal.localDate, type = SymptomType.NAUSEA, intensity = 3, durationMinutes = 20, mealId = 3))
        val sleeps = (1 until 10).map { i ->
            val end = RecordTime.of(from.plusDays(i.toLong()), LocalTime.of(7, 0), zone)
            SleepEntity(id = i.toLong(), startMillis = end.epochMillis - 8 * 3_600_000L, endMillis = end.epochMillis, zoneId = zone.id, localDate = end.epochDay, durationMinutes = 480, source = DataSource.HEALTH_CONNECT, externalId = "s$i", sourceApp = "com.sec.android.app.shealth")
        }
        val activity = (0 until 10).map { i -> DailyActivity(localDate = from.plusDays(i.toLong()).toEpochDay(), steps = 5000L + i * 100, stepsSource = DataSource.HEALTH_CONNECT, hrMin = 55, hrAvg = 70, hrMax = 120) }
        val notes = listOf(NoteEntity(id = 1, epochMillis = meals[0].meal.epochMillis, zoneId = zone.id, localDate = meals[0].meal.localDate, text = "Заметка"))
        val input = AnalyticsInput(from.toEpochDay(), to.toEpochDay(), meals.map { it.meal }, drinks, weights, symptoms, sleeps, activity, notes)
        return ReportData(
            from, to, UserSettings(userName = "Тест", heightCm = 170), meals, drinks, weights, symptoms, sleeps, activity, emptyList(), notes,
            AnalyticsCalculator.series(input), AnalyticsCalculator.trackedDays(input),
        )
    }

    @Test
    fun csvJsonAndArchiveContainAllSections() {
        val data = sample()
        val exporters = DataExporters(context)

        val events = ByteArrayOutputStream().also { exporters.writeEventsCsv(data, it) }.toString(Charsets.UTF_8)
        assertThat(events).startsWith(CsvWriter.BOM + "date,time,timezone,category")
        assertThat(events).contains("\"Суп, \"\"домашний\"\"\"")
        assertThat(events.lines().size).isGreaterThan(40)

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ByteArrayOutputStream().also { exporters.writeCsvArchive(data, it) }.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
            }
        }
        assertThat(names).containsExactly("meals.csv", "drinks.csv", "weights.csv", "symptoms.csv", "sleep.csv", "activity.csv", "exercises.csv", "notes.csv")

        val json = ByteArrayOutputStream().also { exporters.writeJson(data, it) }.toString(Charsets.UTF_8)
        val parsed = Json.decodeFromString(JsonExport.serializer(), json)
        assertThat(parsed.meals).hasSize(10)
        assertThat(parsed.sleep).hasSize(9)
        assertThat(parsed.activity.first().steps).isEqualTo(5000)
    }

    /** Robolectric has no native PdfDocument, so pages are rendered into bitmaps to exercise the layout code. */
    private class BitmapSink : PageSink {
        val pages = mutableListOf<Bitmap>()
        override fun startPage(number: Int, width: Int, height: Int): Canvas {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            pages += bmp
            return Canvas(bmp)
        }
        override fun finishPage() = Unit
    }

    @Test
    fun pdfReportsAreLaidOut() {
        val data = sample()
        val photos = PhotoStorage(context)
        // A real JPEG so the photo section is exercised too.
        val photoName = "report-test.jpg"
        photos.file(photoName).outputStream().use { Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val withPhoto = data.copy(meals = data.meals.mapIndexed { i, m -> if (i == 0) m.copy(photos = listOf(com.nutrix.app.database.entity.MealPhotoEntity(1, m.meal.id, photoName, 0))) else m })
        val generator = PdfReportGenerator(context, photos)
        listOf(false, true).forEach { doctor ->
            val sink = BitmapSink()
            val pages = generator.render(withPhoto, doctorMode = doctor, includePhotos = true, sink = sink)
            assertThat(pages).isAtLeast(2)
            assertThat(sink.pages).hasSize(pages)
        }
        // Empty period still yields a valid single report page.
        val empty = data.copy(meals = emptyList(), drinks = emptyList(), weights = emptyList(), symptoms = emptyList(), sleeps = emptyList(), activity = emptyList(), notes = emptyList(), trackedDays = emptySet())
        assertThat(generator.render(empty, doctorMode = true, includePhotos = false, sink = BitmapSink())).isAtLeast(1)
    }
}
