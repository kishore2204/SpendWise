package com.spendwise.app.transaction.parsing

/**
 * One bank's SMS knowledge (ADR-006): its DLT sender tokens, its own reject patterns, and ordered, anchored
 * templates. A template is `verified` only when a REAL anonymized sample backs it (meta-tested).
 */
interface BankParser {
    val bankCode: String
    val bankName: String
    val senderTokens: Set<String>
    val templates: List<Template>
    val rejects: List<Regex> get() = emptyList()
}

class Template(
    val id: String,
    val regex: Regex,
    val verified: Boolean,
    val map: (MatchResult, RawSms) -> ParsedTxn?,
)

/** Group value or null. */
fun MatchResult.g(name: String): String? = groups[name]?.value?.trim()?.ifEmpty { null }

/** DLT headers look like "VM-INDBNK-S" / "AD-HDFCBK-T": strip the 2-letter operator prefix and the 1-letter suffix. */
object SenderIds {
    private val DLT = Regex("""^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$""")

    /** The middle token for DLT senders; null for phone numbers and anything else (never trusted). */
    fun token(sender: String): String? = DLT.matchEntire(sender.trim().uppercase())?.groupValues?.get(1)
}
