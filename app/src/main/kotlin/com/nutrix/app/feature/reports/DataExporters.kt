package com.nutrix.app.feature.reports

import android.content.Context
import com.nutrix.app.BuildConfig
import com.nutrix.app.R
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.time.RecordTime
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.IntakePlanEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealPhotoEntity
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import com.nutrix.app.domain.DailyActivity
import com.nutrix.app.domain.Nutrient
import com.nutrix.app.domain.intake
import com.nutrix.app.domain.withoutEnergy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.Writer
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** RFC 4180 CSV writer (quotes fields containing separators, quotes or line breaks). */
class CsvWriter(private val out: Writer, private val separator: Char = ',') {
    fun row(vararg values: Any?) = row(values.toList())

    fun row(values: List<Any?>) {
        out.write(values.joinToString(separator.toString()) { escape(it?.toString().orEmpty()) })
        out.write("\r\n")
    }

    fun escape(value: String): String =
        if (value.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    companion object {
        /** UTF-8 byte order mark so spreadsheet apps detect Cyrillic correctly. */
        const val BOM = "\uFEFF"
    }
}

@Serializable
data class JsonExport(
    val app: String = "NUTRIX",
    val appVersion: String,
    val exportedAt: Long,
    val from: String,
    val to: String,
    val meals: List<MealEntity>,
    val mealPhotos: List<MealPhotoEntity>,
    val drinks: List<DrinkEntity>,
    val weights: List<WeightEntity>,
    val symptoms: List<SymptomEntity>,
    val sleep: List<SleepEntity>,
    val activity: List<DailyActivity>,
    val exercises: List<ExerciseEntity>,
    val notes: List<NoteEntity>,
    /** Days the user confirmed as fully recorded (ISO dates). */
    val daysRecordedFully: List<String> = emptyList(),
    /** Calorie and macronutrient guidelines with the epoch day each starts on. */
    val intakePlans: List<IntakePlanEntity> = emptyList(),
)

private fun round1(v: Double) = kotlin.math.round(v * 10) / 10

@Singleton
class DataExporters @Inject constructor(@ApplicationContext private val context: Context) {
    private fun s(id: Int) = context.getString(id)

    private fun dt(epochMillis: Long, zoneId: String): Triple<String, String, String> {
        val rt = RecordTime(epochMillis, zoneId)
        return Triple(rt.localDate.toString(), Formatters.time(rt.localTime), zoneId)
    }

    fun writeJson(data: ReportData, out: OutputStream) {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val export = JsonExport(
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            from = data.from.toString(),
            to = data.to.toString(),
            meals = data.meals.map { it.meal },
            mealPhotos = data.meals.flatMap { it.photos },
            drinks = data.drinks,
            weights = data.weights,
            symptoms = data.symptoms,
            sleep = data.sleeps,
            activity = data.activity,
            exercises = data.exercises,
            notes = data.notes,
            daysRecordedFully = data.confirmedDays.sorted().map { LocalDate.ofEpochDay(it).toString() },
            intakePlans = data.plans,
        )
        out.write(json.encodeToString(JsonExport.serializer(), export).toByteArray())
    }

    /** All events of the period in a single chronological table. */
    fun writeEventsCsv(data: ReportData, out: OutputStream) {
        val writer = out.bufferedWriter()
        writer.write(CsvWriter.BOM)
        val csv = CsvWriter(writer)
        csv.row("date", "time", "timezone", "category", "type", "title", "value", "unit", "details", "note", "source")
        data class Row(val millis: Long, val values: List<Any?>)
        val rows = mutableListOf<Row>()
        data.meals.forEach { mw ->
            val m = mw.meal
            val (d, t, z) = dt(m.epochMillis, m.zoneId)
            val details = listOfNotNull(
                m.appetite?.let { "${s(R.string.food_appetite)}: $it/5" },
                m.portion?.let { "${s(R.string.food_portion)}: ${s(it.labelRes)}" },
                m.satiety?.let { s(it.labelRes) },
                m.feelings.takeIf { it.isNotEmpty() }?.joinToString { s(it.labelRes) },
                m.dishes?.takeIf { it.size > 1 }?.let { d -> s(R.string.food_dishes_title).format(d.size) + ": " + d.joinToString { it.name } }
                    ?: m.items.takeIf { it.isNotEmpty() }?.joinToString(),
                m.caloriesEstimate?.let { "≈$it ${s(R.string.unit_kcal)}" },
                m.portionGrams?.let { "$it ${s(R.string.unit_g)}" },
                m.nutrition?.proteinGrams?.let { "${s(R.string.ai_protein)}: ≈$it" },
                m.nutrition?.fatGrams?.let { "${s(R.string.ai_fat)}: ≈$it" },
                m.nutrition?.carbsGrams?.let { "${s(R.string.ai_carbs)}: ≈$it" },
            ).joinToString("; ")
            rows += Row(m.epochMillis, listOf(d, t, z, s(R.string.category_meal), s(m.mealType.labelRes), m.title, mw.photos.size.takeIf { it > 0 }, "photos", details, m.note, s(R.string.source_manual)))
        }
        data.drinks.forEach { x ->
            val (d, t, z) = dt(x.epochMillis, x.zoneId)
            val energy = if (x.withoutEnergy) null else x.intake.kcal?.let { "≈${Math.round(it)} ${s(R.string.unit_kcal)}" } ?: s(R.string.drink_kcal_unknown)
            rows += Row(x.epochMillis, listOf(d, t, z, s(R.string.category_drink), s(x.drinkType.labelRes), x.name, x.volumeMl, "ml", energy, x.note, s(R.string.source_manual)))
        }
        data.weights.forEach { w ->
            val (d, t, z) = dt(w.epochMillis, w.zoneId)
            rows += Row(w.epochMillis, listOf(d, t, z, s(R.string.category_weight), null, null, "%.2f".format(java.util.Locale.ROOT, w.weightKg), "kg", w.sourceApp, w.note, s(w.source.labelRes)))
        }
        data.symptoms.forEach { x ->
            val (d, t, z) = dt(x.epochMillis, x.zoneId)
            val details = listOfNotNull(x.durationMinutes?.let { "$it min" }, x.position?.let { s(it.labelRes) }, x.mealId?.let { "meal #$it" }).joinToString("; ")
            rows += Row(x.epochMillis, listOf(d, t, z, s(R.string.category_symptom), s(x.type.labelRes), x.customName, x.intensity, "0-5", details, x.note, s(R.string.source_manual)))
        }
        data.sleeps.forEach { x ->
            val (d, t, z) = dt(x.startMillis, x.zoneId)
            val end = dt(x.endMillis, x.zoneId)
            rows += Row(x.startMillis, listOf(d, t, z, s(R.string.category_sleep), null, "${end.first} ${end.second}", x.durationMinutes, "min", x.sourceApp, x.note, s(x.source.labelRes)))
        }
        data.exercises.forEach { x ->
            val (d, t, z) = dt(x.startMillis, x.zoneId)
            rows += Row(x.startMillis, listOf(d, t, z, s(R.string.category_activity), x.exerciseType, x.title, (x.endMillis - x.startMillis) / 60_000, "min", x.kcal?.let { "kcal=${it.toInt()}" }, null, s(x.source.labelRes)))
        }
        data.activity.forEach { a ->
            val date = LocalDate.ofEpochDay(a.localDate).toString()
            if (a.steps != null) rows += Row(LocalDate.ofEpochDay(a.localDate).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), listOf(date, null, null, s(R.string.category_activity), "steps", null, a.steps, "steps", null, null, a.stepsSource?.let { s(it.labelRes) }))
            if (a.hrAvg != null) rows += Row(LocalDate.ofEpochDay(a.localDate).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), listOf(date, null, null, s(R.string.category_activity), "heart_rate", null, a.hrAvg, "bpm", "min=${a.hrMin}; max=${a.hrMax}", null, a.hrSource?.let { s(it.labelRes) }))
        }
        data.notes.forEach { n ->
            val (d, t, z) = dt(n.epochMillis, n.zoneId)
            rows += Row(n.epochMillis, listOf(d, t, z, s(R.string.category_note), null, null, null, null, null, n.text, s(R.string.source_manual)))
        }
        rows.sortedBy { it.millis }.forEach { csv.row(it.values) }
        writer.flush()
    }

    /** ZIP with one CSV per section. */
    fun writeCsvArchive(data: ReportData, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, block: (CsvWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val w = zip.bufferedWriter()
                w.write(CsvWriter.BOM)
                block(CsvWriter(w))
                w.flush()
                zip.closeEntry()
            }
            entry("meals.csv") { c ->
                c.row("id", "date", "time", "timezone", "meal_type", "title", "items", "appetite_0_5", "portion", "satiety", "after_meal", "after_meal_other", "weight_kg", "portion_g", "kcal_approx", "note", "photos", "protein_g_approx", "fat_g_approx", "carbs_g_approx", "estimate_model")
                data.meals.forEach { mw ->
                    val m = mw.meal
                    val (d, t, z) = dt(m.epochMillis, m.zoneId)
                    c.row(m.id, d, t, z, s(m.mealType.labelRes), m.title, m.items.joinToString("; "), m.appetite, m.portion?.let { s(it.labelRes) },
                        m.satiety?.let { s(it.labelRes) }, m.feelings.joinToString("; ") { s(it.labelRes) }, m.feelingOther, m.weightKg,
                        m.portionGrams, m.caloriesEstimate, m.note, mw.photos.size, m.nutrition?.proteinGrams, m.nutrition?.fatGrams, m.nutrition?.carbsGrams, m.nutrition?.estimateModel)
                }
            }
            // Served amounts per dish; the meal row above holds their eaten totals.
            entry("meal_dishes.csv") { c ->
                c.row("meal_id", "dish_id", "name", "components", "served_g", "eaten_share", "kcal_served", "protein_g_served", "fat_g_served",
                    "carbs_g_served", "source", "estimate_model", "photos")
                data.meals.forEach { mw ->
                    mw.meal.dishes.orEmpty().forEach { d ->
                        c.row(mw.meal.id, d.id, d.name, d.components.joinToString("; "), d.servedGrams, d.eatenShare, d.kcal, d.proteinGrams, d.fatGrams,
                            d.carbsGrams, d.source.name.lowercase(), d.estimateModel, d.photos.size)
                    }
                }
            }
            // Energy as the diary counts it for this drink; "unknown" stays empty, it is never written as zero.
            entry("drinks.csv") { c ->
                c.row("id", "date", "time", "timezone", "type", "name", "volume_ml", "note", "energy", "kcal_approx", "protein_g_approx",
                    "fat_g_approx", "carbs_g_approx", "entered_per", "meal_id")
                data.drinks.forEach { x ->
                    val (d, t, z) = dt(x.epochMillis, x.zoneId)
                    val i = x.intake
                    val energy = when { x.withoutEnergy -> "none"; i.kcal == null -> "unknown"; else -> "value" }
                    c.row(x.id, d, t, z, s(x.drinkType.labelRes), x.name, x.volumeMl, x.note, energy, i.kcal?.let(::round1), i.protein?.let(::round1),
                        i.fat?.let(::round1), i.carbs?.let(::round1), x.nutrition?.takeIf { !it.isNone }?.basis?.name?.lowercase(), x.mealId)
                }
            }
            // One row per day of the period, computed by the same rule as the diary and the charts.
            entry("days.csv") { c ->
                c.row("date", "state", "all_recorded_confirmed", "records", "kcal_known_approx", "kcal_records_estimated", "protein_g_known",
                    "protein_records_estimated", "fat_g_known", "fat_records_estimated", "carbs_g_known", "carbs_records_estimated")
                data.intake.forEach { day ->
                    val totals = Nutrient.entries.map { day.total(it) }
                    c.row(LocalDate.ofEpochDay(day.day), day.state(Nutrient.KCAL).name.lowercase(), day.confirmed, day.records,
                        *totals.flatMap { listOf(it.value?.let(::round1), it.known) }.toTypedArray())
                }
            }
            entry("weights.csv") { c ->
                c.row("id", "date", "time", "timezone", "weight_kg", "source", "source_app", "note")
                data.weights.forEach { x -> val (d, t, z) = dt(x.epochMillis, x.zoneId); c.row(x.id, d, t, z, x.weightKg, s(x.source.labelRes), x.sourceApp, x.note) }
            }
            entry("symptoms.csv") { c ->
                c.row("id", "date", "time", "timezone", "symptom", "custom_name", "intensity_0_5", "duration_min", "position", "related_meal_id", "note")
                data.symptoms.forEach { x ->
                    val (d, t, z) = dt(x.epochMillis, x.zoneId)
                    c.row(x.id, d, t, z, s(x.type.labelRes), x.customName, x.intensity, x.durationMinutes, x.position?.let { s(it.labelRes) }, x.mealId, x.note)
                }
            }
            entry("sleep.csv") { c ->
                c.row("id", "start_date", "start_time", "end_date", "end_time", "timezone", "duration_min", "source", "source_app", "note")
                data.sleeps.forEach { x ->
                    val st = dt(x.startMillis, x.zoneId)
                    val en = dt(x.endMillis, x.zoneId)
                    c.row(x.id, st.first, st.second, en.first, en.second, x.zoneId, x.durationMinutes, s(x.source.labelRes), x.sourceApp, x.note)
                }
            }
            entry("activity.csv") { c ->
                c.row("date", "steps", "active_kcal", "total_kcal", "exercise_min", "hr_min", "hr_avg", "hr_max", "steps_source", "hr_source")
                data.activity.forEach { a ->
                    c.row(LocalDate.ofEpochDay(a.localDate), a.steps, a.activeKcal?.toInt(), a.totalKcal?.toInt(), a.exerciseMinutes, a.hrMin, a.hrAvg, a.hrMax,
                        a.stepsSource?.let { s(it.labelRes) }, a.hrSource?.let { s(it.labelRes) })
                }
            }
            entry("exercises.csv") { c ->
                c.row("id", "date", "start", "end", "timezone", "type_code", "title", "kcal", "source", "source_app")
                data.exercises.forEach { x ->
                    val st = dt(x.startMillis, x.zoneId)
                    val en = dt(x.endMillis, x.zoneId)
                    c.row(x.id, st.first, st.second, en.second, x.zoneId, x.exerciseType, x.title, x.kcal?.toInt(), s(x.source.labelRes), x.sourceApp)
                }
            }
            entry("notes.csv") { c ->
                c.row("id", "date", "time", "timezone", "text")
                data.notes.forEach { x -> val (d, t, z) = dt(x.epochMillis, x.zoneId); c.row(x.id, d, t, z, x.text) }
            }
        }
    }
}
