package com.spendwise.app.core.merchant

/**
 * Merchant normalisation (ADR-006 §Merchant normalisation), shared by manual entry and the SMS parsers.
 * The key is what merchant→category rules are stored under, so "ANANTHAM RETAIL PRIVATE L" and
 * "Anantham Retail Pvt Ltd" must produce the same key.
 */
object Merchants {

    private val HONORIFICS = setOf("MR", "MRS", "MS", "MISS", "SHRI", "SMT", "DR", "M/S")
    private val GATEWAY_PREFIXES = listOf("RAZORPAY*", "PAYU*", "PAYTM*", "CCAVENUE*", "BILLDESK*", "UPI-", "POS ")

    // Full and bank-truncated corporate suffixes (HDFC cuts payee names at ~25 chars: "PRIVATE L").
    private val SUFFIXES = Regex(
        "\\b(PRIVATE LIMITED|PRIVATE LIMITE|PRIVATE LIMIT|PRIVATE LIMI|PRIVATE LIM|PRIVATE LI|PRIVATE L|PRIVATE|" +
            "PVT LTD|PVT LT|PVT L|PVT|LIMITED|LIMITE|LTD|LLP|INC|CORPORATION|CORP|CO)\\.?$",
    )

    /** Normalised key, or null when nothing meaningful remains. */
    fun key(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        // A VPA is the most stable identity there is.
        if ('@' in s && ' ' !in s) return s.lowercase()
        s = s.uppercase()
        GATEWAY_PREFIXES.forEach { if (s.startsWith(it)) s = s.removePrefix(it) }
        s = s.replace(Regex("[^A-Z0-9&/ ]"), " ").replace(Regex("\\s+"), " ").trim()
        val words = s.split(' ').toMutableList()
        while (words.size > 1 && words.first().trimEnd('.') in HONORIFICS) words.removeAt(0)
        s = words.joinToString(" ")
        repeat(3) { s = s.replace(SUFFIXES, "").trim() }
        s = s.replace("&", " AND ").replace(Regex("\\s+"), " ").trim()
        return s.ifEmpty { null }
    }

    /** Title-cased display name: "SWIGGY BANGALORE" → "Swiggy Bangalore". VPAs are shown as-is. */
    fun display(raw: String): String {
        val t = raw.trim()
        if ('@' in t && ' ' !in t) return t
        return t.lowercase().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }

    /** Person-to-person payee (honorific prefix), used to default the category to "needs category". */
    fun looksLikePerson(raw: String?): Boolean {
        val first = raw?.trim()?.uppercase()?.split(Regex("\\s+"))?.firstOrNull()?.trimEnd('.') ?: return false
        return first in HONORIFICS && first != "M/S"
    }
}

/**
 * Shipped merchant dictionary (ADR-001 fallback). Keys are substrings of the merchant key; values are the stable
 * seeded category IDs from DatabaseSetup. Learned rules always win over this.
 */
object MerchantDictionary {
    const val FOOD = 10L
    const val GROCERIES = 11L
    const val SHOPPING = 12L
    const val BILLS = 13L
    const val TRANSPORT = 14L
    const val FUEL = 15L
    const val ENTERTAINMENT = 16L
    const val SUBSCRIPTIONS = 17L
    const val EDUCATION = 18L
    const val HEALTH = 19L
    const val TRAVEL = 20L

    private val ENTRIES: List<Pair<String, Long>> = listOf(
        "SWIGGY" to FOOD, "ZOMATO" to FOOD, "HUNGERBOX" to FOOD, "DOMINOS" to FOOD, "MCDONALD" to FOOD, "KFC" to FOOD,
        "STARBUCKS" to FOOD, "BIRYANI" to FOOD, "CAFE" to FOOD, "RESTAURANT" to FOOD, "HOTEL" to FOOD, "BAKERY" to FOOD,
        "BLINKIT" to GROCERIES, "ZEPTO" to GROCERIES, "BIGBASKET" to GROCERIES, "INSTAMART" to GROCERIES,
        "DMART" to GROCERIES, "RELIANCE FRESH" to GROCERIES, "MORE RETAIL" to GROCERIES, "SUPERMARKET" to GROCERIES,
        "AMAZON" to SHOPPING, "FLIPKART" to SHOPPING, "MYNTRA" to SHOPPING, "AJIO" to SHOPPING, "MEESHO" to SHOPPING,
        "NYKAA" to SHOPPING, "DECATHLON" to SHOPPING, "RETAIL" to SHOPPING,
        "AIRTEL" to BILLS, "JIO" to BILLS, "VODAFONE" to BILLS, "BSNL" to BILLS, "ELECTRICITY" to BILLS, "TNEB" to BILLS,
        "BESCOM" to BILLS, "TANGEDCO" to BILLS, "GAS" to BILLS, "BROADBAND" to BILLS, "RECHARGE" to BILLS,
        "UBER" to TRANSPORT, "OLA" to TRANSPORT, "RAPIDO" to TRANSPORT, "METRO" to TRANSPORT, "IRCTC" to TRAVEL,
        "REDBUS" to TRAVEL, "MAKEMYTRIP" to TRAVEL, "INDIGO" to TRAVEL, "GOIBIBO" to TRAVEL, "FASTAG" to TRANSPORT,
        "INDIAN OIL" to FUEL, "IOCL" to FUEL, "BHARAT PETROLEUM" to FUEL, "BPCL" to FUEL, "HPCL" to FUEL, "PETROL" to FUEL,
        "NETFLIX" to SUBSCRIPTIONS, "SPOTIFY" to SUBSCRIPTIONS, "HOTSTAR" to SUBSCRIPTIONS, "PRIME VIDEO" to SUBSCRIPTIONS,
        "YOUTUBE" to SUBSCRIPTIONS, "GOOGLE PLAY" to SUBSCRIPTIONS, "APPLE" to SUBSCRIPTIONS,
        "BOOKMYSHOW" to ENTERTAINMENT, "PVR" to ENTERTAINMENT, "INOX" to ENTERTAINMENT,
        "APOLLO" to HEALTH, "PHARMACY" to HEALTH, "MEDICAL" to HEALTH, "HOSPITAL" to HEALTH, "PHARMEASY" to HEALTH,
        "1MG" to HEALTH, "UDEMY" to EDUCATION, "COURSERA" to EDUCATION, "BYJU" to EDUCATION,
    )

    fun categoryFor(merchantKey: String?): Long? {
        val k = merchantKey?.uppercase() ?: return null
        return ENTRIES.firstOrNull { (needle, _) -> k.contains(needle) }?.second
    }
}
