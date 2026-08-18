package com.nihyli.cloverpromotions.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

@Database(entities = [PromoRule::class], version = 3, exportSchema = false)
@TypeConverters(PromoConverters::class)
abstract class PromoDatabase : RoomDatabase() {
    abstract fun rules(): PromoRuleDao

    companion object {
        @Volatile
        private var instance: PromoDatabase? = null

        /**
         * v1 stored one item per rule (itemId/itemName columns). v2 stores a
         * group of items as JSON plus a display label. SQLite on Clover devices
         * predates DROP COLUMN, so we recreate the table and copy each old row
         * into a single-item group.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE promo_rules_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        label TEXT NOT NULL,
                        items TEXT NOT NULL,
                        requiredQty INTEGER NOT NULL,
                        bundlePriceCents INTEGER NOT NULL,
                        active INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.query(
                    "SELECT id, name, itemId, itemName, requiredQty, bundlePriceCents, active FROM promo_rules",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: ""
                        val itemId = cursor.getString(2) ?: ""
                        val itemName = cursor.getString(3) ?: ""
                        val requiredQty = cursor.getInt(4)
                        val bundlePriceCents = cursor.getLong(5)
                        val active = cursor.getInt(6)
                        val items = JSONArray().put(
                            JSONObject().put("id", itemId).put("name", itemName),
                        ).toString()
                        db.execSQL(
                            "INSERT INTO promo_rules_new (id, name, label, items, requiredQty, bundlePriceCents, active) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(id, name, itemName, items, requiredQty, bundlePriceCents, active),
                        )
                    }
                }
                db.execSQL("DROP TABLE promo_rules")
                db.execSQL("ALTER TABLE promo_rules_new RENAME TO promo_rules")
            }
        }

        /**
         * v3 adds promo kind, schedule, max uses, and savings tracking.
         * Existing rows stay BUNDLE + FIXED_PRICE so live ring prices do not change.
         * Item JSON is left as-is; missing `priceCents` parses as 0.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN kind TEXT NOT NULL DEFAULT 'BUNDLE'")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN percentOff INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN buyQty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN getQty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN maxUsesPerOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN daysOfWeek INTEGER NOT NULL DEFAULT 127")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN startMinute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN endMinute INTEGER NOT NULL DEFAULT 1440")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN bundlePriceMode TEXT NOT NULL DEFAULT 'FIXED_PRICE'")
                db.execSQL("ALTER TABLE promo_rules ADD COLUMN savingsCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): PromoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PromoDatabase::class.java,
                    "promotions.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
