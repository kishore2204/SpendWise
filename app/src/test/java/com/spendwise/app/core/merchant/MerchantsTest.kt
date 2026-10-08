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
        assertThat(MerchantDictionary.categoryFor("ACME ENTERPRISES XYZ")).isNull()
    }

    private fun cat(name: String) = MerchantDictionary.categoryFor(Merchants.key(name))

    @Test fun `named merchants beat generic keywords`() {
        assertThat(cat("DMART")).isEqualTo(MerchantDictionary.GROCERIES)
        assertThat(cat("Bata Shoe Store")).isEqualTo(MerchantDictionary.FOOTWEAR)
        assertThat(cat("APOLLO PHARMACY STORE")).isEqualTo(MerchantDictionary.HEALTH)
        assertThat(cat("Swiggy Instamart")).isEqualTo(MerchantDictionary.GROCERIES) // longer brand beats "Swiggy"
        assertThat(cat("SWIGGY")).isEqualTo(MerchantDictionary.FOOD)
    }

    @Test fun `generic store never overrides a more specific word`() {
        assertThat(cat("Sri Murugan Medical Store")).isEqualTo(MerchantDictionary.HEALTH)
        assertThat(cat("Lakshmi Grocery Store")).isEqualTo(MerchantDictionary.GROCERIES)
        assertThat(cat("Ganesh Store")).isEqualTo(MerchantDictionary.SHOPPING) // only the weak word matched
        assertThat(cat("KUMAR MEDICALS")).isEqualTo(MerchantDictionary.HEALTH) // plural of a keyword
    }

    @Test fun `whole words only — no accidental substrings`() {
        assertThat(cat("COCA COLA BOTTLING")).isNull() // not Ola
        assertThat(cat("LABEL HOUSE")).isNull() // not lab
        assertThat(cat("TEAM SPORTS")).isNull() // not tea
    }

    @Test fun `spacing, apostrophes, VPAs and glued brand names still match`() {
        assertThat(cat("BIG BASKET")).isEqualTo(MerchantDictionary.GROCERIES)
        assertThat(cat("DOMINOS PIZZA")).isEqualTo(MerchantDictionary.FOOD)
        assertThat(cat("MCDONALDS")).isEqualTo(MerchantDictionary.FOOD)
        assertThat(cat("zomato.order@ptybl")).isEqualTo(MerchantDictionary.FOOD)
        assertThat(cat("BLINKITCOMMERCE")).isEqualTo(MerchantDictionary.GROCERIES)
        assertThat(cat("MAKE MY TRIP INDIA")).isEqualTo(MerchantDictionary.TRAVEL)
    }

    @Test fun `payment apps carry no category`() {
        assertThat(cat("PHONEPE")).isNull()
        assertThat(cat("Google Pay")).isNull()
        assertThat(cat("AMAZON PAY INDIA")).isNull() // the payment app, not Amazon shopping
        assertThat(cat("AMAZON SELLER SERVICES")).isEqualTo(MerchantDictionary.SHOPPING)
    }
}
