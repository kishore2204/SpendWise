package com.spendwise.app.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MoneyTest {

    @Test fun `parses bank-style amounts to paise without floating point`() {
        assertThat(Money.parseRupees("Rs.23.00")).isEqualTo(2300L)
        assertThat(Money.parseRupees("Rs.2844.44")).isEqualTo(284444L)
        assertThat(Money.parseRupees("2,844.44")).isEqualTo(284444L)
        assertThat(Money.parseRupees("₹1,23,456")).isEqualTo(12345600L)
        assertThat(Money.parseRupees(Money.format(12345670L))).isEqualTo(12345670L)
        assertThat(Money.parseRupees("INR 99.5")).isEqualTo(9950L)
        assertThat(Money.parseRupees("0.07")).isEqualTo(7L)
        assertThat(Money.parseRupees(".5")).isEqualTo(50L)
        // 0.1 + 0.2 style traps: exact in paise.
        assertThat(Money.parseRupees("4640.10")).isEqualTo(464010L)
    }

    @Test fun `rejects ambiguous or malformed amounts instead of guessing`() {
        listOf("", "Rs.", "abc", "12.345", "1.2.3", "-5", "12.", "1e5", "₹ 12 34", "99999999999999999").forEach {
            assertThat(Money.parseRupees(it)).isNull()
        }
    }

    @Test fun `formats with Indian digit grouping`() {
        assertThat(Money.format(12345670L)).isEqualTo("₹\u20091,23,456.70")
        assertThat(Money.format(2845000L)).isEqualTo("₹\u200928,450")
        assertThat(Money.format(99L)).isEqualTo("₹\u20090.99")
        assertThat(Money.format(0L)).isEqualTo("₹\u20090")
        assertThat(Money.format(100_00_00_000_00L)).isEqualTo("₹\u20091,00,00,00,000")
        assertThat(Money.format(2845000L, alwaysShowPaise = true)).isEqualTo("₹\u200928,450.00")
    }

    @Test fun `sign styles use a true minus sign`() {
        assertThat(Money.format(-32000L)).isEqualTo("−₹\u2009320")
        assertThat(Money.format(32000L, Money.SignStyle.Always)).isEqualTo("+₹\u2009320")
        assertThat(Money.format(-32000L, Money.SignStyle.None)).isEqualTo("₹\u2009320")
        assertThat(Money.format(0L, Money.SignStyle.Always)).isEqualTo("₹\u20090")
    }

    @Test fun `compact form truncates and never overstates`() {
        assertThat(Money.formatCompact(95000L)).isEqualTo("₹\u2009950")
        assertThat(Money.formatCompact(1_249_900L)).isEqualTo("₹\u200912.4K")
        assertThat(Money.formatCompact(1_249_999_00L)).isEqualTo("₹\u200912.4L")
        assertThat(Money.formatCompact(3_29_00_000_00L)).isEqualTo("₹\u20093.2Cr")
        assertThat(Money.formatCompact(-1_00_000_00L)).isEqualTo("−₹\u20091L")
    }

    @Test fun `groups digits the Indian way`() {
        assertThat(Money.groupIndian(1L)).isEqualTo("1")
        assertThat(Money.groupIndian(999L)).isEqualTo("999")
        assertThat(Money.groupIndian(1000L)).isEqualTo("1,000")
        assertThat(Money.groupIndian(100000L)).isEqualTo("1,00,000")
        assertThat(Money.groupIndian(1234567L)).isEqualTo("12,34,567")
    }
}
