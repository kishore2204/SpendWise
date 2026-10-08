package com.spendwise.app.core.money

/**
 * Money is always an integer count of paise (CLAUDE.md: never Float/Double).
 * These helpers are the only sanctioned way to turn text into money and money into text.
 */
object Money {

    private const val PAISE_PER_RUPEE = 100L

    /**
     * Parses a rupee amount such as "2,844.44", "Rs.23.00", "₹1,23,456", "INR 99.5" into paise.
     * Returns null for anything that is not an unambiguous non-negative amount with at most 2 decimals.
     * Never throws, and never goes through floating point.
     */
    fun parseRupees(text: String): Long? {
        var s = text.trim()
        for (prefix in listOf("Rs.", "Rs", "INR", "₹")) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trimStart()
                break
            }
        }
        s = s.replace(",", "")
        if (s.isEmpty() || s.length > 15) return null

        val dot = s.indexOf('.')
        val wholePart = if (dot >= 0) s.substring(0, dot) else s
        val fracPart = if (dot >= 0) s.substring(dot + 1) else ""
        if (wholePart.isEmpty() && fracPart.isEmpty()) return null
        if (!wholePart.all { it in '0'..'9' } || !fracPart.all { it in '0'..'9' }) return null
        if (fracPart.length > 2) return null
        if (dot >= 0 && fracPart.isEmpty()) return null

        val rupees = if (wholePart.isEmpty()) 0L else wholePart.toLongOrNull() ?: return null
        val paise = fracPart.padEnd(2, '0').ifEmpty { "0" }.toLong()
        if (rupees > Long.MAX_VALUE / PAISE_PER_RUPEE - 1) return null
        return rupees * PAISE_PER_RUPEE + paise
    }

    /**
     * Formats paise as INR with Indian digit grouping and a thin visual gap after ₹: 12345670 → "₹ 1,23,456.70".
     * Paise are omitted when zero unless [alwaysShowPaise]. The sign is controlled by [sign].
     */
    fun format(
        paise: Long,
        sign: SignStyle = SignStyle.NegativeOnly,
        alwaysShowPaise: Boolean = false,
    ): String {
        val negative = paise < 0
        val abs = if (paise == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(paise)
        val rupees = abs / PAISE_PER_RUPEE
        val fraction = abs % PAISE_PER_RUPEE
        val body = buildString {
            append('₹').append('\u2009')
            append(groupIndian(rupees))
            if (alwaysShowPaise || fraction != 0L) {
                append('.')
                append(fraction.toString().padStart(2, '0'))
            }
        }
        val prefix = when (sign) {
            SignStyle.None -> ""
            SignStyle.NegativeOnly -> if (negative) "−" else ""
            SignStyle.Always -> when {
                negative -> "−"
                paise > 0 -> "+"
                else -> ""
            }
        }
        return prefix + body
    }

    /**
     * Compact form for tight spaces and very large font scales: ₹950, ₹12.4K, ₹12.4L, ₹3.2Cr.
     * Truncates (never rounds up) to one decimal so the figure never overstates the amount.
     */
    fun formatCompact(paise: Long): String {
        val negative = paise < 0
        val rupees = kotlin.math.abs(paise / PAISE_PER_RUPEE)
        val (unit, suffix) = when {
            rupees >= 1_00_00_000L -> 1_00_00_000L to "Cr"
            rupees >= 1_00_000L -> 1_00_000L to "L"
            rupees >= 1_000L -> 1_000L to "K"
            else -> 1L to ""
        }
        val text = if (unit == 1L) {
            "₹\u2009$rupees"
        } else {
            val tenths = rupees * 10 / unit
            val whole = tenths / 10
            val dec = tenths % 10
            if (dec == 0L) "₹\u2009$whole$suffix" else "₹\u2009$whole.$dec$suffix"
        }
        return if (negative) "−$text" else text
    }

    /** 1234567 → "12,34,567" (last three digits, then groups of two). */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val sb = StringBuilder()
        val firstGroupLen = rest.length % 2
        if (firstGroupLen > 0) sb.append(rest, 0, firstGroupLen)
        var i = firstGroupLen
        while (i < rest.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(rest, i, i + 2)
            i += 2
        }
        return "$sb,$last3"
    }

    enum class SignStyle { None, NegativeOnly, Always }
}
