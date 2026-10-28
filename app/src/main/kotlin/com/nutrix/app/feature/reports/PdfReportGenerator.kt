package com.nutrix.app.feature.reports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.nutrix.app.domain.SleepTotals
import com.nutrix.app.R
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.photo.PhotoStorage
import com.nutrix.app.core.time.Formatters
import com.nutrix.app.core.ui.labelRes
import com.nutrix.app.domain.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Destination of rendered pages; [AndroidPdfSink] in the app, a bitmap sink in tests. */
interface PageSink {
    fun startPage(number: Int, width: Int, height: Int): Canvas
    fun finishPage()
}

class AndroidPdfSink : PageSink {
    val document = PdfDocument()
    private var page: PdfDocument.Page? = null

    override fun startPage(number: Int, width: Int, height: Int): Canvas {
        val p = document.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
        page = p
        return p.canvas
    }

    override fun finishPage() {
        page?.let(document::finishPage)
        page = null
    }
}

/**
 * Builds an A4 PDF with [PdfDocument]. Doctor mode keeps strictly factual wording
 * (no interpretations, no analytics conclusions).
 */
@Singleton
class PdfReportGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photos: PhotoStorage,
) {
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    fun write(data: ReportData, doctorMode: Boolean, includePhotos: Boolean, out: OutputStream) {
        val sink = AndroidPdfSink()
        try {
            render(data, doctorMode, includePhotos, sink)
            sink.document.writeTo(out)
        } finally {
            sink.document.close()
        }
    }

    /** Lays out the whole report into [sink]; returns the number of pages. */
    fun render(data: ReportData, doctorMode: Boolean, includePhotos: Boolean, sink: PageSink): Int {
        val w = PageWriter(sink)
        val unitKg = s(R.string.unit_kg)
        val h = s(R.string.unit_hour_short)
        val m = s(R.string.unit_minute_short)

        // Title
        w.text(s(if (doctorMode) R.string.pdf_doctor_title else R.string.pdf_title), w.title)
        w.text(s(R.string.pdf_period, Formatters.fullDate(data.from), Formatters.fullDate(data.to)), w.body)
        w.text(s(R.string.pdf_generated, Formatters.dateTime(System.currentTimeMillis(), ZoneId.systemDefault().id)), w.small)
        if (data.settings.userName.isNotBlank()) w.text(s(R.string.pdf_patient, data.settings.userName), w.body)
        data.settings.heightCm?.let { w.text(s(R.string.pdf_height, it), w.body) }
        w.space(6f)
        w.box(s(R.string.pdf_disclaimer))
        w.space(10f)

        // Summary
        w.heading(s(R.string.pdf_summary))
        val series = data.series
        val weights = data.weights.sortedBy { it.epochMillis }
        val summary = buildList {
            add(s(R.string.pdf_days_tracked) to "${data.trackedDays.size} / ${data.to.toEpochDay() - data.from.toEpochDay() + 1}")
            series[Metric.MEALS]?.average?.let { add(s(R.string.pdf_meals_avg) to Formatters.decimal(it)) }
            series[Metric.FLUID]?.average?.let { add(s(R.string.pdf_fluid_avg) to "${it.toInt()} ${s(R.string.unit_ml)}") }
            series[Metric.APPETITE]?.average?.let { add(s(R.string.pdf_appetite_avg) to Formatters.decimal(it)) }
            add(s(R.string.pdf_early_satiety) to (series[Metric.EARLY_SATIETY]?.points?.sumOf { it.value }?.toInt() ?: 0).toString())
            add(s(R.string.pdf_nausea) to (series[Metric.NAUSEA]?.points?.sumOf { it.value }?.toInt() ?: 0).toString())
            if (weights.isNotEmpty()) {
                add(s(R.string.pdf_weight_first_last) to "${Formatters.decimal(weights.first().weightKg)} → ${Formatters.decimal(weights.last().weightKg)} $unitKg")
                add(s(R.string.pdf_weight_min_max) to "${Formatters.decimal(weights.minOf { it.weightKg })} / ${Formatters.decimal(weights.maxOf { it.weightKg })} $unitKg")
            }
            series[Metric.SLEEP_DURATION]?.average?.let { add(s(R.string.pdf_sleep_avg) to Formatters.duration((it * 60).toLong(), h, m)) }
            series[Metric.STEPS]?.average?.let { add(s(R.string.pdf_steps_avg) to Formatters.integer(it.toLong())) }
            val hr = data.activity.filter { it.hrAvg != null || it.hrMin != null }
            if (hr.isNotEmpty()) {
                val min = hr.mapNotNull { it.hrMin }.minOrNull()
                val avg = hr.mapNotNull { it.hrAvg }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                val max = hr.mapNotNull { it.hrMax }.maxOrNull()
                add(s(R.string.pdf_hr) to "${min ?: "—"} / ${avg ?: "—"} / ${max ?: "—"} ${s(R.string.unit_bpm)}")
            }
            add(s(R.string.pdf_symptoms_total) to data.symptoms.size.toString())
        }
        w.keyValues(summary)

        // Symptom frequency
        if (data.symptoms.isNotEmpty()) {
            w.space(6f)
            w.subheading(s(R.string.pdf_symptom_counts))
            val counts = data.symptoms.groupBy { it.type }.map { (type, list) ->
                val intensities = list.mapNotNull { it.intensity }
                s(type.labelRes) to (list.size.toString() + if (intensities.isNotEmpty()) " (${s(R.string.pdf_intensity, intensities.average().toInt())})" else "")
            }.sortedByDescending { it.second.substringBefore(' ').toIntOrNull() ?: 0 }
            w.keyValues(counts)
        }

        // Weight chart
        val weightPoints = series[Metric.WEIGHT]?.points.orEmpty()
        if (weightPoints.size >= 2) {
            w.space(8f)
            w.heading(s(R.string.pdf_weight_chart))
            w.lineChart(weightPoints.map { it.epochDay to it.value }) { v -> "${Formatters.decimal(v)} $unitKg" }
        }

        // Daily table
        w.space(8f)
        w.heading(s(R.string.pdf_daily))
        val days = (data.from.toEpochDay()..data.to.toEpochDay()).filter { d ->
            d in data.trackedDays || data.sleeps.any { it.localDate == d } || data.activity.any { it.localDate == d } || data.weights.any { it.localDate == d }
        }
        val header = listOf(s(R.string.pdf_col_date), s(R.string.pdf_col_meals), s(R.string.pdf_col_fluid), s(R.string.pdf_col_appetite), s(R.string.pdf_col_sleep), s(R.string.pdf_col_steps), s(R.string.pdf_col_weight), s(R.string.pdf_col_symptoms))
        val rows = days.map { d ->
            val tracked = d in data.trackedDays
            listOf(
                Formatters.dayMonthShort(LocalDate.ofEpochDay(d)),
                if (tracked) data.meals.count { it.meal.localDate == d }.toString() else "—",
                if (tracked) data.drinks.filter { it.localDate == d }.sumOf { it.volumeMl }.toString() else "—",
                data.meals.filter { it.meal.localDate == d }.mapNotNull { it.meal.appetite }.takeIf { it.isNotEmpty() }?.average()?.let { Formatters.decimal(it) } ?: "—",
                SleepTotals.minutes(data.sleeps.filter { it.localDate == d }).takeIf { it > 0 }?.let { Formatters.duration(it.toLong(), h, m) } ?: "—",
                data.activity.firstOrNull { it.localDate == d }?.steps?.let { Formatters.integer(it) } ?: "—",
                data.weights.filter { it.localDate == d }.takeIf { it.isNotEmpty() }?.map { it.weightKg }?.average()?.let { Formatters.decimal(it) } ?: "—",
                if (tracked) data.symptoms.count { it.localDate == d }.toString() else "—",
            )
        }
        if (rows.isEmpty()) w.text(s(R.string.pdf_none), w.small) else w.table(header, rows, floatArrayOf(1.2f, 0.7f, 1f, 0.9f, 1.1f, 1f, 0.9f, 0.9f))

        // Symptoms
        w.space(8f)
        w.heading(s(R.string.pdf_symptoms))
        if (data.symptoms.isEmpty()) w.text(s(R.string.pdf_none), w.small)
        data.symptoms.sortedBy { it.epochMillis }.forEach { x ->
            val linkedMeal = data.meals.firstOrNull { it.meal.id == x.mealId }?.meal
            val parts = listOfNotNull(
                x.intensity?.let { s(R.string.pdf_intensity, it) },
                x.durationMinutes?.let { s(R.string.pdf_duration, Formatters.duration(it.toLong(), h, m)) },
                x.position?.let { s(it.labelRes) },
                linkedMeal?.let { s(R.string.pdf_after_meal, (it.title ?: s(it.mealType.labelRes)) + " " + Formatters.time(it.time)) },
                x.note,
            )
            w.bullet("${Formatters.dateTime(x.epochMillis, x.zoneId)} — ${x.customName ?: s(x.type.labelRes)}" + if (parts.isNotEmpty()) ": " + parts.joinToString("; ") else "")
        }

        // Meals
        w.space(8f)
        w.heading(s(R.string.pdf_meals))
        if (data.meals.isEmpty()) w.text(s(R.string.pdf_none), w.small)
        data.meals.sortedBy { it.meal.epochMillis }.forEach { mw ->
            val x = mw.meal
            val parts = listOfNotNull(
                x.items.takeIf { it.isNotEmpty() }?.joinToString(),
                x.appetite?.let { "${s(R.string.food_appetite)} $it/5" },
                x.portion?.let { s(it.labelRes) },
                x.satiety?.takeIf { it != Satiety.NORMAL }?.let { s(it.labelRes) },
                x.feelings.filter { it != AfterMealFeeling.NORMAL }.takeIf { it.isNotEmpty() }?.joinToString { s(it.labelRes) },
                x.caloriesEstimate?.let { "≈$it ${s(R.string.unit_kcal)}" },
                x.note,
            )
            w.bullet("${Formatters.dateTime(x.epochMillis, x.zoneId)} — ${s(x.mealType.labelRes)}${x.title?.let { ": $it" } ?: ""}" + if (parts.isNotEmpty()) " (" + parts.joinToString("; ") + ")" else "")
        }
        if (data.meals.any { it.meal.caloriesEstimate != null }) w.text(s(R.string.pdf_kcal_note), w.small)

        // Notes
        if (data.notes.isNotEmpty()) {
            w.space(8f)
            w.heading(s(R.string.pdf_notes))
            data.notes.sortedBy { it.epochMillis }.forEach { n -> w.bullet("${Formatters.dateTime(n.epochMillis, n.zoneId)} — ${n.text}") }
        }

        // Photos
        if (includePhotos) {
            val withPhotos = data.meals.filter { it.photos.isNotEmpty() }.sortedBy { it.meal.epochMillis }.take(60)
            if (withPhotos.isNotEmpty()) {
                w.space(8f)
                w.heading(s(R.string.pdf_photos))
                withPhotos.forEach { mw ->
                    val file = photos.file(mw.sortedPhotos.first().fileName)
                    val bmp = decodeThumb(file.path) ?: return@forEach
                    val caption = "${Formatters.dateTime(mw.meal.epochMillis, mw.meal.zoneId)} ${mw.meal.title ?: s(mw.meal.mealType.labelRes)}"
                    w.photo(bmp, caption)
                    bmp.recycle()
                }
                w.flushPhotos()
            }
        }

        return w.finish()
    }

    private fun decodeThumb(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 400) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Minimal flowing layout engine over PdfDocument pages. */
    private inner class PageWriter(private val sink: PageSink) {
        val pageW = 595
        val pageH = 842
        val margin = 40f
        val contentW = pageW - 2 * margin
        private var pageNumber = 0
        private var pageOpen = false
        private lateinit var canvas: Canvas
        private var y = 0f

        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(15, 120, 108) }
        val headingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(20, 24, 28) }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.rgb(40, 44, 48) }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(25, 28, 32) }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(95, 100, 106) }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 214, 218); strokeWidth = 0.7f }
        private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 156, 140); strokeWidth = 1.6f; style = Paint.Style.STROKE }
        private val fillSoft = Paint().apply { color = Color.rgb(238, 246, 245) }

        private var photoX = 0f
        private var photoRowH = 0f

        init {
            newPage()
        }

        private fun newPage() {
            if (pageOpen) finishPage()
            pageNumber++
            canvas = sink.startPage(pageNumber, pageW, pageH)
            pageOpen = true
            y = margin
        }

        private fun finishPage() {
            canvas.drawText(s(R.string.pdf_page, pageNumber), pageW - margin - 40f, pageH - 20f, small)
            canvas.drawText("NUTRIX", margin, pageH - 20f, small)
            sink.finishPage()
            pageOpen = false
        }

        private fun ensure(height: Float) {
            if (y + height > pageH - margin - 10f) newPage()
        }

        private fun layout(text: String, paint: TextPaint, width: Float = contentW): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()

        fun text(t: String, paint: TextPaint) {
            val l = layout(t, paint)
            ensure(l.height.toFloat())
            canvas.save(); canvas.translate(margin, y); l.draw(canvas); canvas.restore()
            y += l.height + 3f
        }

        fun heading(t: String) {
            ensure(40f)
            y += 4f
            text(t, headingPaint)
            canvas.drawLine(margin, y, margin + contentW, y, accent)
            y += 6f
        }

        fun subheading(t: String) = text(t, subPaint)

        fun space(v: Float) {
            y += v
        }

        fun box(t: String) {
            val l = layout(t, small, contentW - 16f)
            ensure(l.height + 16f)
            canvas.drawRoundRect(RectF(margin, y, margin + contentW, y + l.height + 16f), 6f, 6f, fillSoft)
            canvas.save(); canvas.translate(margin + 8f, y + 8f); l.draw(canvas); canvas.restore()
            y += l.height + 16f
        }

        fun bullet(t: String) {
            val l = layout(t, body, contentW - 12f)
            ensure(l.height.toFloat() + 2f)
            canvas.drawCircle(margin + 3f, y + 6f, 1.6f, headingPaint)
            canvas.save(); canvas.translate(margin + 12f, y); l.draw(canvas); canvas.restore()
            y += l.height + 3f
        }

        fun keyValues(items: List<Pair<String, String>>) {
            items.forEach { (k, v) ->
                val kl = layout(k, body, contentW * 0.6f)
                val vl = layout(v, subPaint, contentW * 0.4f)
                val hgt = maxOf(kl.height, vl.height).toFloat() + 4f
                ensure(hgt)
                canvas.save(); canvas.translate(margin, y); kl.draw(canvas); canvas.restore()
                canvas.save(); canvas.translate(margin + contentW * 0.6f, y); vl.draw(canvas); canvas.restore()
                y += hgt
                canvas.drawLine(margin, y - 2f, margin + contentW, y - 2f, line)
            }
        }

        fun table(header: List<String>, rows: List<List<String>>, weights: FloatArray) {
            val total = weights.sum()
            val widths = weights.map { it / total * contentW }
            fun drawRow(cells: List<String>, paint: TextPaint, shaded: Boolean) {
                val layouts = cells.mapIndexed { i, c -> layout(c, paint, widths[i] - 4f) }
                val hgt = layouts.maxOf { it.height }.toFloat() + 6f
                ensure(hgt)
                if (shaded) canvas.drawRect(margin, y, margin + contentW, y + hgt, fillSoft)
                var x = margin
                layouts.forEachIndexed { i, l ->
                    canvas.save(); canvas.translate(x + 2f, y + 3f); l.draw(canvas); canvas.restore()
                    x += widths[i]
                }
                y += hgt
            }
            drawRow(header, subPaint, shaded = true)
            rows.forEachIndexed { i, r ->
                if (y + 20f > pageH - margin - 10f) {
                    newPage()
                    drawRow(header, subPaint, shaded = true)
                }
                drawRow(r, body, shaded = i % 2 == 1)
                canvas.drawLine(margin, y, margin + contentW, y, line)
            }
        }

        fun lineChart(points: List<Pair<Long, Double>>, label: (Double) -> String) {
            val height = 150f
            ensure(height + 20f)
            val left = margin + 50f
            val right = margin + contentW
            val top = y + 6f
            val bottom = y + height
            val min = points.minOf { it.second }
            val max = points.maxOf { it.second }
            val span = (max - min).takeIf { it > 1e-6 } ?: 1.0
            val x0 = points.first().first
            val xSpan = (points.last().first - x0).coerceAtLeast(1)
            canvas.drawLine(left, bottom, right, bottom, line)
            canvas.drawLine(left, top, left, bottom, line)
            canvas.drawText(label(max), margin, top + 8f, small)
            canvas.drawText(label(min), margin, bottom, small)
            canvas.drawText(Formatters.dayMonthShort(LocalDate.ofEpochDay(points.first().first)), left, bottom + 12f, small)
            val lastLabel = Formatters.dayMonthShort(LocalDate.ofEpochDay(points.last().first))
            canvas.drawText(lastLabel, right - small.measureText(lastLabel), bottom + 12f, small)
            val path = Path()
            points.forEachIndexed { i, (d, v) ->
                val px = left + (d - x0).toFloat() / xSpan * (right - left)
                val py = bottom - ((v - min) / span * (bottom - top - 8f)).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                canvas.drawCircle(px, py, 2f, headingPaint)
            }
            canvas.drawPath(path, accent)
            y = bottom + 20f
        }

        fun photo(bmp: Bitmap, caption: String) {
            val size = (contentW - 16f) / 3f
            if (photoX + size > contentW + 1f) {
                y += photoRowH + 6f
                photoX = 0f
                photoRowH = 0f
            }
            val capLayout = layout(caption, small, size)
            val blockH = size + capLayout.height + 4f
            if (y + blockH > pageH - margin - 10f) {
                newPage()
                photoX = 0f
                photoRowH = 0f
            }
            val scale = minOf(size / bmp.width, size / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            val left = margin + photoX + (size - w) / 2
            canvas.drawBitmap(bmp, null, RectF(left, y, left + w, y + h), null)
            canvas.save(); canvas.translate(margin + photoX, y + size + 2f); capLayout.draw(canvas); canvas.restore()
            photoX += size + 8f
            photoRowH = maxOf(photoRowH, blockH)
        }

        fun flushPhotos() {
            y += photoRowH + 6f
            photoX = 0f
            photoRowH = 0f
        }

        fun finish(): Int {
            if (pageOpen) finishPage()
            return pageNumber
        }
    }
}
