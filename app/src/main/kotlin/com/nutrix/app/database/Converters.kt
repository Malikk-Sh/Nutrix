package com.nutrix.app.database

import androidx.room.TypeConverter
import com.nutrix.app.core.model.AfterMealFeeling
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    @TypeConverter
    fun stringListToJson(list: List<String>): String = json.encodeToString(stringList, list)

    @TypeConverter
    fun jsonToStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(stringList, value) }.getOrDefault(emptyList())

    @TypeConverter
    fun feelingsToString(list: List<AfterMealFeeling>): String = list.joinToString(",") { it.name }

    @TypeConverter
    fun stringToFeelings(value: String?): List<AfterMealFeeling> =
        value.orEmpty().split(',').filter { it.isNotBlank() }
            .mapNotNull { name -> AfterMealFeeling.entries.firstOrNull { it.name == name } }
}
