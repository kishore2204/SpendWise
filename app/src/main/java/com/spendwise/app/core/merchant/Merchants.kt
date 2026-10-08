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
