package com.spendwise.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParseSignedRupeesTest {
    @Test fun `plain, grouped and signed amounts`() {
        assertThat(parseSignedRupees("2568.44")).isEqualTo(256844)
        assertThat(parseSignedRupees("1,23,456.70")).isEqualTo(12345670)
        assertThat(parseSignedRupees("123,456")).isEqualTo(12345600)
        assertThat(parseSignedRupees("1,234")).isEqualTo(123400)
        assertThat(parseSignedRupees("-500")).isEqualTo(-50000)
        assertThat(parseSignedRupees("\u221212,000")).isEqualTo(-1200000) // true minus, as Money.format prints it
    }

    @Test fun `decimal comma and misplaced grouping are rejected, never read 100x too large`() {
        assertThat(parseSignedRupees("2844,44")).isNull()
        assertThat(parseSignedRupees("12,34")).isNull()
        assertThat(parseSignedRupees("1,2345")).isNull()
        assertThat(parseSignedRupees("12.345")).isNull()
    }

    @Test fun `empty is an error unless explicitly allowed`() {
        assertThat(parseSignedRupees("")).isNull()
        assertThat(parseSignedRupees("", emptyIsZero = true)).isEqualTo(0)
    }
}
