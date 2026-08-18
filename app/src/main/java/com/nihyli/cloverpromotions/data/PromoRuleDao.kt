package com.nihyli.cloverpromotions.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PromoRuleDao {
    @Query("SELECT * FROM promo_rules ORDER BY id DESC")
    fun observeAll(): Flow<List<PromoRule>>

    @Query("SELECT * FROM promo_rules WHERE active = 1")
    suspend fun activeRules(): List<PromoRule>

    @Upsert
    suspend fun upsert(rule: PromoRule)

    @Delete
    suspend fun delete(rule: PromoRule)
}
