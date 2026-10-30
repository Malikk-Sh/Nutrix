package com.nutrix.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nutrix.app.database.dao.DayCheckDao
import com.nutrix.app.database.dao.DrinkDao
import com.nutrix.app.database.dao.HealthDao
import com.nutrix.app.database.dao.IntakePlanDao
import com.nutrix.app.database.dao.MealDao
import com.nutrix.app.database.dao.NoteDao
import com.nutrix.app.database.dao.SymptomDao
import com.nutrix.app.database.dao.WeightDao
import com.nutrix.app.database.entity.ActivityDailyEntity
import com.nutrix.app.database.entity.DayCheckEntity
import com.nutrix.app.database.entity.DraftEntity
import com.nutrix.app.database.entity.DrinkEntity
import com.nutrix.app.database.entity.DrinkTemplateEntity
import com.nutrix.app.database.entity.ExerciseEntity
import com.nutrix.app.database.entity.FavoriteMealEntity
import com.nutrix.app.database.entity.IntakePlanEntity
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
        DayCheckEntity::class,
        IntakePlanEntity::class,
    ],
    version = 5,
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
    abstract fun dayCheckDao(): DayCheckDao
    abstract fun intakePlanDao(): IntakePlanDao

    companion object {
        const val NAME = "nutrix.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN nutrition TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE favorite_meals ADD COLUMN nutrition TEXT DEFAULT NULL")
            }
        }

        /** Dishes of a meal. Existing meals keep their whole-meal estimate: nothing is split for them. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN dishes TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE favorite_meals ADD COLUMN dishes TEXT DEFAULT NULL")
            }
        }

        /**
         * Energy of drinks, the meal a drink was had with, and days confirmed as fully recorded. Existing
         * drinks keep an unknown energy: an old coffee is not assumed to have been without milk.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drinks ADD COLUMN nutrition TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE drinks ADD COLUMN mealId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drinks_mealId` ON `drinks` (`mealId`)")
                db.execSQL("ALTER TABLE drink_templates ADD COLUMN nutrition TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS `day_checks` (`localDate` INTEGER NOT NULL, `confirmedAt` INTEGER NOT NULL, PRIMARY KEY(`localDate`))")
            }
        }

        /** Calorie and macronutrient guidelines with the day each starts on; nothing exists for earlier days. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `intake_plans` (`startDay` INTEGER NOT NULL, `source` TEXT NOT NULL, `kcalMin` INTEGER, " +
                        "`kcalMax` INTEGER, `proteinMin` INTEGER, `proteinMax` INTEGER, `fatMin` INTEGER, `fatMax` INTEGER, `carbsMin` INTEGER, " +
                        "`carbsMax` INTEGER, `method` TEXT, `basis` TEXT DEFAULT NULL, `note` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`startDay`))",
                )
            }
        }

        fun build(context: Context, name: String = NAME): NutrixDatabase =
            Room.databaseBuilder(context, NutrixDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
    @Provides fun dayCheckDao(db: NutrixDatabase): DayCheckDao = db.dayCheckDao()
    @Provides fun intakePlanDao(db: NutrixDatabase): IntakePlanDao = db.intakePlanDao()
}
