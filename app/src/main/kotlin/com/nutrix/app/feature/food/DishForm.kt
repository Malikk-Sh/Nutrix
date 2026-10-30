package com.nutrix.app.feature.food

import com.nutrix.app.core.model.DishSource
import com.nutrix.app.core.model.MealDish
import com.nutrix.app.core.util.Units
import kotlinx.serialization.Serializable
import java.util.UUID

/** A dish as the editor holds it: numbers stay text until saved, so a half-typed value is never lost. */
@Serializable
data class DishForm(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val components: List<String> = emptyList(),
    val gramsText: String = "",
    val share: Double = 1.0,
    val kcalText: String = "",
    val proteinText: String = "",
    val fatText: String = "",
    val carbsText: String = "",
    val source: DishSource = DishSource.MANUAL,
    val photos: List<String> = emptyList(),
    val estimateModel: String? = null,
    val uncertainty: String? = null,
) {
    val texts: List<String> get() = listOf(gramsText, kcalText, proteinText, fatText, carbsText)
    val invalid: Boolean get() = texts.any { it.isNotBlank() && parse(it) == null }

    fun toDish() = MealDish(
        id = id, name = name.trim(), components = components, servedGrams = parse(gramsText), eatenShare = share,
        kcal = parse(kcalText), proteinGrams = parse(proteinText), fatGrams = parse(fatText), carbsGrams = parse(carbsText),
        source = source, photos = photos, estimateModel = estimateModel, uncertainty = uncertainty,
    )

    companion object {
        fun parse(text: String): Double? = Units.parseDecimal(text)?.takeIf { it.isFinite() && it in 0.0..100_000.0 }
        fun text(value: Double?): String = value?.let { Units.roundTo(it, 1).toString().removeSuffix(".0").replace('.', ',') }.orEmpty()

        fun of(d: MealDish) = DishForm(
            id = d.id, name = d.name, components = d.components, gramsText = text(d.servedGrams), share = d.eatenShare,
            kcalText = text(d.kcal), proteinText = text(d.proteinGrams), fatText = text(d.fatGrams), carbsText = text(d.carbsGrams),
            source = d.source, photos = d.photos, estimateModel = d.estimateModel, uncertainty = d.uncertainty,
        )

        /** The same dish again (another meal, a favorite): a new identity and no photos of the old day. */
        fun copyOf(d: MealDish, source: DishSource) = of(d).copy(id = UUID.randomUUID().toString(), photos = emptyList(), source = source)
    }
}
