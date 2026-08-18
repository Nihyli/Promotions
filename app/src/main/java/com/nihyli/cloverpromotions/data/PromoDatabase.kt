package com.nihyli.cloverpromotions.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PromoRule::class], version = 1, exportSchema = false)
abstract class PromoDatabase : RoomDatabase() {
    abstract fun rules(): PromoRuleDao

    companion object {
        @Volatile
        private var instance: PromoDatabase? = null

        fun get(context: Context): PromoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PromoDatabase::class.java,
                    "promotions.db",
                ).build().also { instance = it }
            }
    }
}
