package com.spendwise.app.domain.analytics

import com.spendwise.app.core.time.IndiaTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Calendar periods used by Home and Insights. Weeks are Monday through Sunday in India time. */
enum class ReportPeriod(val label: String) {
    DAY("Today"), WEEK("This week"), MONTH("This month"), YEAR("This year");

    /** Short granularity names used by Insights; Home retains the more conversational labels above. */
    val granularityLabel: String
        get() = when (this) {
            DAY -> "Day"
            WEEK -> "Week"
            MONTH -> "Month"
            YEAR -> "Year"
        }

    fun range(today: LocalDate, offset: Int = 0): ReportDateRange = when (this) {
        DAY -> {
            val day = today.plusDays(offset.toLong())
            ReportDateRange(day, day)
        }
        WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
            ReportDateRange(monday, if (offset == 0) today else monday.plusDays(6))
        }
        MONTH -> {
            val month = YearMonth.from(today).plusMonths(offset.toLong())
            ReportDateRange(month.atDay(1), if (offset == 0) today else month.atEndOfMonth())
        }
        YEAR -> {
            val year = today.plusYears(offset.toLong()).year
            ReportDateRange(LocalDate.of(year, 1, 1), if (offset == 0) today else LocalDate.of(year, 12, 31))
        }
    }

    fun label(range: ReportDateRange): String = when (this) {
        DAY -> range.start.format(DAY_FORMAT)
        WEEK -> "${range.start.format(SHORT_DATE)} – ${range.endInclusive.format(SHORT_DATE)}"
        MONTH -> YearMonth.from(range.start).format(MONTH_FORMAT)
        YEAR -> if (range.endInclusive.monthValue == 12 && range.endInclusive.dayOfMonth == 31) range.start.year.toString() else "${range.start.year} to date"
    }

    fun previous(range: ReportDateRange): ReportDateRange = when (this) {
        DAY -> range.shiftDays(-1)
        WEEK -> range.shiftDays(-7)
        MONTH -> range.start.minusMonths(1).let { ReportDateRange(it.withDayOfMonth(1), it.withDayOfMonth(1).plusMonths(1).minusDays(1)) }
        YEAR -> {
            val previousStart = range.start.minusYears(1)
            val fullYear = range.endInclusive.monthValue == 12 && range.endInclusive.dayOfMonth == 31
            ReportDateRange(previousStart, if (fullYear) previousStart.withMonth(12).withDayOfMonth(31) else previousStart.plusDays(range.dayCount.toLong() - 1))
        }
    }

    companion object {
        private val DAY_FORMAT = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM")
        private val SHORT_DATE = java.time.format.DateTimeFormatter.ofPattern("d MMM")
        private val MONTH_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")

        fun today(nowMillis: Long): LocalDate = IndiaTime.localDate(nowMillis)
    }
}

data class ReportDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init { require(!endInclusive.isBefore(start)) { "End date must not be before start date" } }

    val fromEpochDay: Long get() = start.toEpochDay()
    val toEpochDay: Long get() = endInclusive.toEpochDay()
    val dayCount: Int get() = (toEpochDay - fromEpochDay + 1).toInt()

    fun shiftDays(days: Long) = ReportDateRange(start.plusDays(days), endInclusive.plusDays(days))
}
