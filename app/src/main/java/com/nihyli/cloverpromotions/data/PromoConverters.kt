package com.nihyli.cloverpromotions.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/** Serializes a promotion's item list (and enums) for Room columns. */
class PromoConverters {
    @TypeConverter
    fun itemsToJson(items: List<PromoItemRef>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("priceCents", item.priceCents),
            )
        }
        return array.toString()
    }

    @TypeConverter
    fun jsonToItems(json: String?): List<PromoItemRef> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isNullOrBlank()) continue
                add(
                    PromoItemRef(
                        id = id,
                        name = obj.optString("name"),
                        priceCents = obj.optLong("priceCents", 0L),
                    ),
                )
            }
        }
    }

    @TypeConverter
    fun promoKindToString(kind: PromoKind): String = kind.name

    @TypeConverter
    fun stringToPromoKind(value: String?): PromoKind =
        value?.let { runCatching { PromoKind.valueOf(it) }.getOrNull() } ?: PromoKind.BUNDLE

    @TypeConverter
    fun bundlePriceModeToString(mode: BundlePriceMode): String = mode.name

    @TypeConverter
    fun stringToBundlePriceMode(value: String?): BundlePriceMode =
        value?.let { runCatching { BundlePriceMode.valueOf(it) }.getOrNull() }
            ?: BundlePriceMode.FIXED_PRICE
}
