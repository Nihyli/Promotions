package com.nihyli.cloverpromotions.ui

import java.util.Locale

fun centsToDollars(cents: Long): String =
    String.format(Locale.US, "$%.2f", cents / 100.0)

/** Parses user input like "5", "5.0", "5.00" into cents; null if invalid. */
fun dollarsToCents(input: String): Long? {
    val trimmed = input.trim().removePrefix("$")
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return Math.round(value * 100)
}
