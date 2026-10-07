package com.spendwise.app.core.merchant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MerchantsTest {
    @Test fun `bank-truncated and full corporate names share one key`() {
        assertThat(Merchants.key("ANANTHAM RETAIL PRIVATE L")).isEqualTo("ANANTHAM RETAIL")
        assertThat(Merchants.key("Anantham Retail Pvt Ltd")).isEqualTo("ANANTHAM RETAIL")
        assertThat(Merchants.key("Anantham Retail Private Limited")).isEqualTo("ANANTHAM RETAIL")
    }

    @Test fun `honorifics, gateway prefixes and punctuation are removed`() {
        assertThat(Merchants.key("Mr Ravi K")).isEqualTo("RAVI K")
        assertThat(Merchants.key("RAZORPAY*SWIGGY")).isEqualTo("SWIGGY")
        assertThat(Merchants.key("  CUMTA.  ")).isEqualTo("CUMTA")
    }

    @Test fun `a VPA is its own key`() {
        assertThat(Merchants.key("ravi.k@okhdfcbank")).isEqualTo("ravi.k@okhdfcbank")
    }

    @Test fun `blank is no merchant`() {
        assertThat(Merchants.key("  ")).isNull()
        assertThat(Merchants.key(null)).isNull()
    }

    @Test fun `person detection uses honorifics`() {
        assertThat(Merchants.looksLikePerson("Mr Ravi K")).isTrue()
        assertThat(Merchants.looksLikePerson("HungerBox")).isFalse()
    }

    @Test fun `dictionary maps common merchants`() {
        assertThat(MerchantDictionary.categoryFor("SWIGGY")).isEqualTo(MerchantDictionary.FOOD)
        assertThat(MerchantDictionary.categoryFor("HUNGERBOX")).isEqualTo(MerchantDictionary.FOOD)
        assertThat(MerchantDictionary.categoryFor("UBER INDIA")).isEqualTo(MerchantDictionary.TRANSPORT)
        assertThat(MerchantDictionary.categoryFor("UNKNOWN SHOP XYZ")).isNull()
    }
}
