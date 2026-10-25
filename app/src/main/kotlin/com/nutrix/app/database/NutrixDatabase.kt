package com.nutrix.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nutrix.app.database.dao.DrinkDao
import com.nutrix.app.database.dao.HealthDao
import com.nutrix.app.database.dao.MealDao
import com.nutrix.app.database.dao.NoteDao
import com.nutrix.app.database.dao.SymptomDao
import com.nutrix.app.database.dao.WeightDao
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.DraftEntity
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.MealEntity
import com.nutrix.app.database.entity.MealPhotoEntity
import com.nutrix.app.database.entity.NoteEntity
import com.nutrix.app.database.entity.SleepEntity
import com.nutrix.app.database.entity.SymptomEntity
import com.nutrix.app.database.entity.WeightEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        MealEntity::class,
        MealPhotoEntity::class,
        DrinkEntity::class,
        DrinkTemplateEntity::class,
        WeightEntity::class,
        SymptomEntity::class,
        SleepEntity::class,
        ActivityDailyEntity::class,
        ExerciseEntity::class,
        NoteEntity::class,
        FavoriteMealEntity::class,
        DraftEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NutrixDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun drinkDao(): DrinkDao
    abstract fun weightDao(): WeightDao
    abstract fun symptomDao(): SymptomDao
    abstract fun healthDao(): HealthDao
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME = "nutrix.db"

        fun build(context: Context): NutrixDatabase =
            Room.databaseBuilder(context, NutrixDatabase::class.java, NAME)
                // Future schema changes must ship real migrations; never wipe user diaries.
                .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NutrixDatabase = NutrixDatabase.build(context)

    @Provides fun mealDao(db: NutrixDatabase): MealDao = db.mealDao()
    @Provides fun drinkDao(db: NutrixDatabase): DrinkDao = db.drinkDao()
    @Provides fun weightDao(db: NutrixDatabase): WeightDao = db.weightDao()
    @Provides fun symptomDao(db: NutrixDatabase): SymptomDao = db.symptomDao()
    @Provides fun healthDao(db: NutrixDatabase): HealthDao = db.healthDao()
    @Provides fun noteDao(db: NutrixDatabase): NoteDao = db.noteDao()
}
