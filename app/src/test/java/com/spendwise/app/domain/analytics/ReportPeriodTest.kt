package com.spendwise.app.domain.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReportPeriodTest {
    private val today = LocalDate.of(2026, 10, 20)

    @Test fun `current week is Monday to date while prior weeks are complete Monday through Sunday`() {
        val range = ReportPeriod.WEEK.range(today)
        assertThat(range.start).isEqualTo(LocalDate.of(2026, 10, 19))
        assertThat(range.endInclusive).isEqualTo(today)
        assertThat(ReportPeriod.WEEK.range(today, -1).start).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(ReportPeriod.WEEK.range(today, -1).endInclusive).isEqualTo(LocalDate.of(2026, 10, 18))
        assertThat(ReportPeriod.WEEK.previous(range)).isEqualTo(ReportDateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)))
        assertThat(ReportPeriod.WEEK.label(range)).isEqualTo("19 Oct – 20 Oct")
    }

    @Test fun `month and year periods respect leap years and calendar boundaries`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        assertThat(ReportPeriod.MONTH.range(leapDay).dayCount).isEqualTo(29)
        assertThat(ReportPeriod.MONTH.range(today).endInclusive).isEqualTo(today)
        assertThat(ReportPeriod.YEAR.range(leapDay).start).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(ReportPeriod.YEAR.range(leapDay).endInclusive).isEqualTo(leapDay)
        assertThat(ReportPeriod.YEAR.range(today, -1).endInclusive).isEqualTo(LocalDate.of(2025, 12, 31))
        assertThat(ReportPeriod.MONTH.range(today, -1).start).isEqualTo(LocalDate.of(2026, 9, 1))
    }

    @Test fun `range rejects reversed dates`() {
        val result = runCatching { ReportDateRange(today, today.minusDays(1)) }
        assertThat(result.isFailure).isTrue()
    }
}
