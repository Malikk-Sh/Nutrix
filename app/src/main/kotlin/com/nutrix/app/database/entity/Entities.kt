package com.nutrix.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.nutrix.app.core.model.AfterMealFeeling
import com.nutrix.app.core.model.BodyPosition
import com.nutrix.app.core.model.DataSource
import com.nutrix.app.core.model.DrinkType
import com.nutrix.app.core.model.MealType
import com.nutrix.app.core.model.MealNutrition
import com.nutrix.app.core.model.PortionEaten
import com.nutrix.app.core.model.Satiety
import com.nutrix.app.core.model.SymptomType
import com.nutrix.app.core.time.RecordTime
import kotlinx.serialization.Serializable

/*
 * Time convention for all diary tables:
 *  - epochMillis: absolute instant of the event
 *  - zoneId: zone the user was in when the event happened
 *  - localDate: epoch day of the event in that zone (used for day grouping)
 */

@Serializable
@Entity(
    tableName = "meals",
    indices = [Index("localDate"), Index("epochMillis")],
)
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val mealType: MealType,
    val title: String? = null,
    val items: List<String> = emptyList(),
    val appetite: Int? = null,
    val portion: PortionEaten? = null,
    val satiety: Satiety? = null,
    val feelings: List<AfterMealFeeling> = emptyList(),
    val feelingOther: String? = null,
    val weightKg: Double? = null,
    val note: String? = null,
    val portionGrams: Int? = null,
    val caloriesEstimate: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val nutrition: MealNutrition? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val time: RecordTime get() = RecordTime(epochMillis, zoneId)
}

@Serializable
@Entity(
    tableName = "meal_photos",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mealId")],
)
data class MealPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    /** File name inside the private photo directory. */
    val fileName: String,
    val position: Int = 0,
)

data class MealWithPhotos(
    @Embedded val meal: MealEntity,
    @Relation(parentColumn = "id", entityColumn = "mealId")
    val photos: List<MealPhotoEntity>,
) {
    val sortedPhotos: List<MealPhotoEntity> get() = photos.sortedBy { it.position }
}

@Serializable
@Entity(tableName = "drinks", indices = [Index("localDate"), Index("epochMillis")])
data class DrinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val drinkType: DrinkType,
    /** Optional custom name, e.g. template name "Моя кружка чая". */
    val name: String? = null,
    val volumeMl: Int,
    val photoFile: String? = null,
    val note: String? = null,
    val templateId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val time: RecordTime get() = RecordTime(epochMillis, zoneId)
}

@Serializable
@Entity(tableName = "drink_templates")
data class DrinkTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val drinkType: DrinkType,
    val volumeMl: Int,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "weights",
    indices = [
        Index("localDate"),
        Index("epochMillis"),
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val weightKg: Double,
    val source: DataSource = DataSource.MANUAL,
    /** Record id in the external store (Health Connect / Samsung Health), null for manual. */
    val externalId: String? = null,
    /** Package that originally wrote the record, e.g. com.sec.android.app.shealth. */
    val sourceApp: String? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val time: RecordTime get() = RecordTime(epochMillis, zoneId)
}

@Serializable
@Entity(
    tableName = "symptoms",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("localDate"), Index("epochMillis"), Index("mealId")],
)
data class SymptomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val type: SymptomType,
    val customName: String? = null,
    /** 0..5, null when not specified (e.g. one-tap quick entry). */
    val intensity: Int? = null,
    val durationMinutes: Int? = null,
    val note: String? = null,
    val mealId: Long? = null,
    val position: BodyPosition? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val time: RecordTime get() = RecordTime(epochMillis, zoneId)
}

@Serializable
@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index("localDate"),
        Index("endMillis"),
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long,
    /** Zone at wake-up time. */
    val zoneId: String,
    /** Epoch day of the wake-up (sleep belongs to the morning it ends on). */
    val localDate: Long,
    val durationMinutes: Int,
    val source: DataSource = DataSource.MANUAL,
    val externalId: String? = null,
    val sourceApp: String? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** One row per day and source with activity aggregates imported from health platforms. */
@Serializable
@Entity(tableName = "activity_daily", primaryKeys = ["localDate", "source"])
data class ActivityDailyEntity(
    val localDate: Long,
    val source: DataSource,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val exerciseMinutes: Int? = null,
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val hrAvg: Int? = null,
    val hrLatestBpm: Int? = null,
    val hrLatestMillis: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "exercises",
    indices = [
        Index("localDate"),
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val exerciseType: Int? = null,
    val title: String? = null,
    val kcal: Double? = null,
    val source: DataSource,
    val externalId: String? = null,
    val sourceApp: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "notes", indices = [Index("localDate"), Index("epochMillis")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMillis: Long,
    val zoneId: String,
    val localDate: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val time: RecordTime get() = RecordTime(epochMillis, zoneId)
}

@Serializable
@Entity(tableName = "favorite_meals")
data class FavoriteMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val items: List<String> = emptyList(),
    val mealType: MealType? = null,
    val portionGrams: Int? = null,
    val caloriesEstimate: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val nutrition: MealNutrition? = null,
    val note: String? = null,
    val photoFile: String? = null,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Unsaved editor state; one draft per editor kind. */
@Serializable
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val kind: String,
    val payload: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class DayValue(val localDate: Long, val value: Double)

data class DayCount(val localDate: Long, val count: Int)
