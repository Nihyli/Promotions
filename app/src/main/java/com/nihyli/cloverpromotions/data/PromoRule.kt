package com.nihyli.cloverpromotions.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar
import java.util.Locale

/**
 * How a promotion computes its discount.
 * Stored in Room v3; only [BUNDLE] is used until later commits.
 */
enum class PromoKind { BUNDLE, PERCENT_OFF, BUY_X_GET_Y }

/**
 * How a [PromoKind.BUNDLE] deal reacts when inventory prices change.
 * Migrated rows stay [FIXED_PRICE] (absolute ring price). New deals default
 * to [TRACK_SAVINGS] in the editor so the original dollar-off is kept.
 */
enum class BundlePriceMode { FIXED_PRICE, TRACK_SAVINGS }

/** One inventory item that belongs to a promotion (e.g. a single Red Bull flavor/SKU). */
data class PromoItemRef(
    val id: String,
    val name: String,
    /** Unit price in cents at the time the merchant saved the deal. Missing in v2 JSON → 0. */
    val priceCents: Long = 0,
)

/**
 * Snapshot retail of a pack of [qty] units from [prices].
 *
 * If [qty] equals the number of selected items, prices are summed (one of each).
 * Otherwise `qty * average(prices)` using integer division.
 */
fun snapshotPackRetail(prices: List<Long>, qty: Int): Long {
    if (prices.isEmpty() || qty <= 0) return 0L
    val sum = prices.sum()
    return if (qty == prices.size) sum else sum * qty / prices.size
}

/**
 * A quantity bundle promotion. Buy [requiredQty] units drawn from any of the
 * promotion's [items] and the bundle rings up at [bundlePriceCents] total.
 *
 * Multiple items (e.g. different flavors/SKUs of the same drink) can share one
 * promotion, and units of any of them count together toward the same deal:
 * 1 red Red Bull + 1 blue Red Bull satisfies a "2 for $5" promo. Units from a
 * different promotion never count toward this one.
 *
 * [label] is the shared display name for the group (e.g. "Red Bull") used in the
 * promotion name and cashier hints.
 *
 * Extra v3 columns (kind, percent/buy-get) stay at defaults until a later commit.
 */
@Entity(tableName = "promo_rules")
data class PromoRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val label: String,
    val items: List<PromoItemRef>,
    val requiredQty: Int,
    val bundlePriceCents: Long,
    val active: Boolean = true,
    val kind: PromoKind = PromoKind.BUNDLE,
    val percentOff: Int = 0,
    val buyQty: Int = 0,
    val getQty: Int = 0,
    val maxUsesPerOrder: Int = 0,
    val daysOfWeek: Int = ALL_DAYS,
    val startMinute: Int = 0,
    val endMinute: Int = END_OF_DAY_MINUTE,
    val bundlePriceMode: BundlePriceMode = BundlePriceMode.FIXED_PRICE,
    val savingsCents: Long = 0,
) {
    /**
     * Short group name for titles and hints (e.g. "Candy", "Red Bull").
     * Ignores a stored label that already looks like a full “3 x … for $…” title.
     */
    fun groupDisplayName(): String {
        val fromItems = items.firstOrNull()?.name.orEmpty()
        val trimmed = label.trim()
        if (trimmed.isEmpty() || looksLikeFullPromoTitle(trimmed)) {
            return fromItems.ifBlank { trimmed }
        }
        return trimmed
    }

    /** Units that make one pack of this deal. */
    fun packSize(): Int = requiredQty

    /** Snapshot retail of one pack, using prices stored on [items]. */
    fun snapshotPackRetail(): Long = snapshotPackRetail(items.map { it.priceCents }, packSize().coerceAtLeast(requiredQty))

    /** Title shown in the list and on receipts. */
    fun displayTitle(): String {
        val group = groupDisplayName()
        return if (bundlePriceMode == BundlePriceMode.TRACK_SAVINGS && savingsCents > 0) {
            "$requiredQty x $group, ${formatMoney(savingsCents)} off"
        } else {
            "$requiredQty x $group for ${formatMoney(bundlePriceCents)}"
        }
    }

    /**
     * True when local [at] falls on an enabled weekday and inside the daily
     * window. Does not look at [active] — the calculator filters that separately.
     *
     * Windows are half-open `[startMinute, endMinute)`. `endMinute == 1440`
     * is end-of-day. When `startMinute > endMinute` the window spans midnight
     * (e.g. 22:00–02:00).
     */
    fun isInEffect(at: Calendar = Calendar.getInstance()): Boolean {
        val dayIndex = at.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        if (dayIndex !in 0..6) return false
        if (daysOfWeek and (1 shl dayIndex) == 0) return false

        val minuteOfDay = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
        val start = startMinute
        val end = endMinute
        return if (start <= end) {
            minuteOfDay >= start && minuteOfDay < end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /** Short schedule label for the list row, or null when the deal is always on. */
    fun scheduleSummary(): String? {
        val daysPart = when (daysOfWeek and ALL_DAYS) {
            ALL_DAYS -> null
            WEEKEND_DAYS -> "Weekends"
            WEEKDAY_DAYS -> "Weekdays"
            0 -> "No days"
            else -> DAY_ABBREV.filterIndexed { i, _ -> daysOfWeek and (1 shl i) != 0 }
                .joinToString("/")
        }
        val allDay = startMinute <= 0 && endMinute >= END_OF_DAY_MINUTE
        val timePart = if (allDay) null else "${formatClock12(startMinute)}–${formatClock12(endMinute)}"
        return when {
            daysPart == null && timePart == null -> null
            daysPart != null && timePart != null -> "$daysPart $timePart"
            else -> daysPart ?: timePart
        }
    }

    companion object {
        /** Bits 0–6 = [Calendar.SUNDAY]..[Calendar.SATURDAY]. */
        const val ALL_DAYS = 0b1111111
        const val WEEKEND_DAYS = 0b1000001
        const val WEEKDAY_DAYS = 0b0111110
        const val END_OF_DAY_MINUTE = 24 * 60

        private val DAY_ABBREV = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }
}

/** True if [text] already includes qty/price wording, so wrapping it again would duplicate. */
fun looksLikeFullPromoTitle(text: String): Boolean {
    val value = text.trim()
    if (value.contains(" for $", ignoreCase = true)) return true
    if (value.contains(" for ", ignoreCase = true) && value.firstOrNull()?.isDigit() == true) return true
    if (Regex("""^\d+\s*x\s""", RegexOption.IGNORE_CASE).containsMatchIn(value)) return true
    return false
}

fun formatMoney(cents: Long): String =
    java.lang.String.format(Locale.US, "$%.2f", cents / 100.0)

/** Parses `H:mm` / `HH:mm` (and a bare hour) into minutes from midnight. `24:00` → 1440. */
fun parseClockMinutes(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val match = Regex("""^(\d{1,2})(?::(\d{2}))?$""").matchEntire(trimmed) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
    if (minute !in 0..59) return null
    if (hour == 24) return if (minute == 0) PromoRule.END_OF_DAY_MINUTE else null
    if (hour !in 0..23) return null
    return hour * 60 + minute
}

fun formatClockMinutes24(minutes: Int): String {
    val clamped = minutes.coerceIn(0, PromoRule.END_OF_DAY_MINUTE)
    val hour = clamped / 60
    val min = clamped % 60
    return String.format(Locale.US, "%02d:%02d", hour, min)
}

internal fun formatClock12(minutes: Int): String {
    val clamped = minutes.coerceIn(0, PromoRule.END_OF_DAY_MINUTE)
    if (clamped >= PromoRule.END_OF_DAY_MINUTE) return "12am"
    val hour24 = clamped / 60
    val min = clamped % 60
    val ampm = if (hour24 < 12) "am" else "pm"
    val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
    return if (min == 0) "$hour12$ampm" else String.format(Locale.US, "%d:%02d%s", hour12, min, ampm)
}
