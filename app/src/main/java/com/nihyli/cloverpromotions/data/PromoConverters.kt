package com.nihyli.cloverpromotions.data

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/** Serializes a promotion's item list to JSON for storage in a single column. */
class PromoConverters {
    @TypeConverter
    fun itemsToJson(items: List<PromoItemRef>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("id", item.id).put("name", item.name))
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
                add(PromoItemRef(id = id, name = obj.optString("name")))
            }
        }
    }
}
