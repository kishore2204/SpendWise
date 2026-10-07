package com.spendwise.app.data.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectedAccountTest {
    @Test fun `keeps the stored account while it is active`() {
        assertThat(resolveSelected(7, listOf(3, 7, 9))).isEqualTo(7)
    }

    @Test fun `falls back to the first active account when the stored one was archived`() {
        assertThat(resolveSelected(7, listOf(3, 9))).isEqualTo(3)
    }

    @Test fun `first launch with accounts picks the first one`() {
        assertThat(resolveSelected(null, listOf(4, 5))).isEqualTo(4)
    }

    @Test fun `no accounts means no selection`() {
        assertThat(resolveSelected(7, emptyList())).isNull()
    }
}
