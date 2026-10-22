package com.jacekgajek.kapelusz.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.math.absoluteValue

/** An amount in minor units of the band's currency (grosze for PLN). Never use floating point for money. */
@Serializable
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(minor + other.minor)
    operator fun minus(other: Money) = Money(minor - other.minor)
    operator fun unaryMinus() = Money(-minor)
    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    val isZero get() = minor == 0L
    val isPositive get() = minor > 0L
    val isNegative get() = minor < 0L

    /** Plain decimal representation, e.g. `-1234.50`. Locale-aware formatting belongs to the UI. */
    override fun toString(): String {
        val abs = minor.absoluteValue
        val sign = if (minor < 0) "-" else ""
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    companion object {
        val ZERO = Money(0)

        /** Parses `123`, `123.4`, `123.45`, `-5.00` (dot or comma as decimal separator). */
        fun parse(text: String): Money {
            val normalized = text.trim().replace(',', '.')
            val match = requireNotNull(Regex("""(-?)(\d+)(?:\.(\d{1,2}))?""").matchEntire(normalized)) {
                "Not a money amount: '$text'"
            }
            val (sign, whole, fraction) = match.destructured
            val minor = whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
            return Money(if (sign == "-") -minor else minor)
        }
    }
}

fun Iterable<Money>.sum(): Money = Money(sumOf { it.minor })

inline fun <T> Iterable<T>.sumOfMoney(selector: (T) -> Money): Money = Money(sumOf { selector(it).minor })
