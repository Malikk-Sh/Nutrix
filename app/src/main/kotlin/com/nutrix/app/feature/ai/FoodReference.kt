package com.nutrix.app.feature.ai

import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.database.entity.MealWithPhotos
import java.time.Instant
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Offline reference of typical energy density (kcal per 100 g) of common dishes.
 * Values are averages from public nutrition tables; real dishes vary widely, so every
 * number derived from this table is shown as an approximate estimate only.
 */
object FoodReference {
    data class Entry(val name: String, val kcalPer100g: Int, val keywords: List<String>, val typicalPortionG: Int)

    val entries: List<Entry> = listOf(
        e("Овсяная каша", 88, 250, "овсян", "овсянк", "геркулес"),
        e("Гречневая каша", 110, 200, "гречк", "гречнев"),
        e("Рис отварной", 130, 180, "рис"),
        e("Макароны отварные", 158, 200, "макарон", "паст", "спагетти"),
        e("Картофельное пюре", 106, 200, "пюре"),
        e("Картофель отварной", 82, 200, "картофель отвар", "картошка отвар"),
        e("Картофель жареный", 192, 180, "картофель жар", "картошка жар", "фри"),
        e("Манная каша", 98, 250, "манн"),
        e("Пшённая каша", 110, 250, "пшён", "пшен"),
        e("Борщ", 49, 300, "борщ"),
        e("Щи", 35, 300, "щи"),
        e("Куриный суп", 36, 300, "куриный суп", "суп кур", "бульон"),
        e("Солянка", 69, 300, "солянк"),
        e("Уха", 46, 300, "уха"),
        e("Суп-пюре овощной", 45, 300, "суп-пюре", "крем-суп"),
        e("Пельмени", 248, 200, "пельмен"),
        e("Вареники с картофелем", 148, 200, "вареник"),
        e("Блины", 233, 150, "блин"),
        e("Оладьи", 260, 150, "олад"),
        e("Сырники", 220, 150, "сырник"),
        e("Омлет", 154, 150, "омлет"),
        e("Яичница", 196, 120, "яичниц", "глазунь"),
        e("Яйцо варёное", 155, 55, "яйцо", "яйца"),
        e("Творог 5%", 121, 150, "творог"),
        e("Йогурт", 66, 150, "йогурт"),
        e("Кефир", 51, 250, "кефир"),
        e("Сыр твёрдый", 360, 30, "сыр"),
        e("Хлеб белый", 265, 30, "хлеб бел", "батон", "булк"),
        e("Хлеб ржаной", 210, 30, "хлеб ржан", "черный хлеб", "чёрный хлеб"),
        e("Бутерброд", 250, 80, "бутерброд", "тост", "сэндвич"),
        e("Куриная грудка", 165, 150, "грудк", "филе кур"),
        e("Курица запечённая", 190, 150, "курица", "куриц", "окорочок", "бедро"),
        e("Котлета", 250, 100, "котлет"),
        e("Тефтели", 190, 150, "тефтел", "фрикадел"),
        e("Говядина тушёная", 220, 150, "говядин", "гуляш"),
        e("Свинина жареная", 300, 150, "свинин"),
        e("Рыба запечённая", 140, 150, "рыба", "треск", "минтай", "хек"),
        e("Лосось", 208, 120, "лосос", "сёмг", "семг", "форел"),
        e("Плов", 200, 250, "плов"),
        e("Голубцы", 120, 250, "голубц"),
        e("Салат овощной", 50, 150, "салат овощ", "овощной салат", "огурц", "помидор"),
        e("Салат оливье", 198, 150, "оливье"),
        e("Винегрет", 76, 150, "винегрет"),
        e("Салат «Цезарь»", 190, 200, "цезар"),
        e("Овощи тушёные", 60, 200, "рагу", "овощи туш", "тушёные овощи"),
        e("Банан", 89, 120, "банан"),
        e("Яблоко", 52, 150, "яблок"),
        e("Апельсин", 47, 150, "апельсин", "мандарин"),
        e("Груша", 57, 150, "груш"),
        e("Виноград", 69, 120, "виноград"),
        e("Ягоды", 45, 100, "ягод", "клубник", "черник", "малин"),
        e("Печенье", 450, 30, "печень"),
        e("Шоколад", 540, 25, "шоколад"),
        e("Торт", 380, 100, "торт", "пирожн"),
        e("Пирожок печёный", 260, 80, "пирож", "пирог", "булочк"),
        e("Пицца", 266, 200, "пицц"),
        e("Бургер", 255, 200, "бургер", "гамбургер"),
        e("Шаурма", 220, 300, "шаурм", "шаверм"),
        e("Суши / роллы", 160, 200, "суши", "ролл"),
        e("Орехи", 600, 30, "орех", "миндал", "фундук"),
        e("Мёд", 304, 20, "мёд", "мед "),
        e("Смузи фруктовый", 60, 300, "смузи"),
        e("Протеиновый коктейль", 80, 300, "протеин", "коктейл"),
        e("Мюсли", 370, 50, "мюсли", "гранол"),
        e("Хлопья с молоком", 120, 250, "хлопь", "кукурузн"),
        e("Лаваш с начинкой", 230, 200, "лаваш", "ролл с"),
        e("Хачапури", 300, 200, "хачапур"),
        e("Манты", 200, 250, "мант", "хинкал"),
        e("Запеканка творожная", 168, 150, "запеканк"),
    )

    private fun e(name: String, kcal: Int, portion: Int, vararg keywords: String) =
        Entry(name, kcal, keywords.toList(), portion)

    /** Best matching reference for a dish name, or null when nothing fits. */
    fun find(query: String): Entry? {
        val q = query.lowercase().replace('ё', 'е')
        if (q.isBlank()) return null
        return entries.map { entry ->
            val score = entry.keywords.maxOf { kw ->
                val k = kw.lowercase().replace('ё', 'е')
                when {
                    q == k -> 100
                    q.startsWith(k) -> 80 + k.length
                    q.contains(k) -> 50 + k.length
                    else -> 0
                }
            }
            entry to score
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun search(prefix: String, limit: Int = 6): List<Entry> {
        val q = prefix.lowercase().replace('ё', 'е').trim()
        if (q.length < 2) return emptyList()
        return entries.filter { it.name.lowercase().replace('ё', 'е').contains(q) || it.keywords.any { k -> k.contains(q) } }.take(limit)
    }

    /**
     * Approximate energy of what was actually eaten:
     * kcal/100 g × served grams × eaten fraction. Always an estimate.
     */
    fun estimateKcal(entry: Entry, servedGrams: Int?, portion: PortionEaten?): Int {
        val grams = servedGrams ?: entry.typicalPortionG
        return (entry.kcalPer100g * grams / 100.0 * (portion?.fraction ?: 1.0)).roundToInt()
    }
}

/** Suggestions derived only from the user's own diary, computed on the device. */
object SmartSuggestions {
    /** Meal type typically logged around this time, falling back to clock-based defaults. */
    fun guessMealType(history: List<MealWithPhotos>, time: LocalTime): MealType {
        val minutes = time.hour * 60 + time.minute
        val near = history.map { it.meal }.filter {
            val t = Instant.ofEpochMilli(it.epochMillis).atZone(it.time.zone).toLocalTime()
            circularDistance(t.hour * 60 + t.minute, minutes) <= 75
        }
        val voted = near.groupingBy { it.mealType }.eachCount().maxByOrNull { it.value }
        if (voted != null && voted.value >= 3) return voted.key
        return when (time.hour) {
            in 5..10 -> MealType.BREAKFAST
            in 11..15 -> MealType.LUNCH
            in 17..21 -> MealType.DINNER
            else -> MealType.SNACK
        }
    }

    /** Dishes the user often logs within ±2 h of [time]; most frequent first. */
    fun dishesForTime(history: List<MealWithPhotos>, time: LocalTime, limit: Int = 5): List<String> {
        val minutes = time.hour * 60 + time.minute
        return history.map { it.meal }
            .filter { !it.title.isNullOrBlank() }
            .filter {
                val t = Instant.ofEpochMilli(it.epochMillis).atZone(it.time.zone).toLocalTime()
                circularDistance(t.hour * 60 + t.minute, minutes) <= 120
            }
            .groupingBy { it.title!!.trim() }
            .eachCount()
            .filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % 1440
        return minOf(d, 1440 - d)
    }
}
