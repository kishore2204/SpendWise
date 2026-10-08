package com.spendwise.app.transaction.parsing

import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.transaction.bankparsers.generic.GenericParser
import com.spendwise.app.transaction.bankparsers.hdfc.HdfcParser
import com.spendwise.app.transaction.bankparsers.indianbank.IndianBankParser
import kotlin.math.abs

/**
 * Banks the app recognises. Only these DLT senders can ever create a transaction (ADR-005 sender gate).
 * Banks without a dedicated parser use the generic parser (≤ NEEDS_REVIEW) so a NEW account can still be
 * detected and offered to the user.
 */
object BankRegistry {
    val parsers: List<BankParser> = listOf(IndianBankParser, HdfcParser)

    /** Token → (canonical code, display name) for banks handled only by the generic parser. */
    private val genericBanks: Map<String, Pair<String, String>> = mapOf(
        "SBIINB" to ("SBI" to "State Bank of India"), "SBIUPI" to ("SBI" to "State Bank of India"),
        "ATMSBI" to ("SBI" to "State Bank of India"), "SBIPSG" to ("SBI" to "State Bank of India"),
        "ICICIB" to ("ICICI" to "ICICI Bank"), "ICICIT" to ("ICICI" to "ICICI Bank"),
        "AXISBK" to ("AXIS" to "Axis Bank"), "KOTAKB" to ("KOTAK" to "Kotak Mahindra Bank"),
        "CANBNK" to ("CANARA" to "Canara Bank"), "IDFCFB" to ("IDFC" to "IDFC First Bank"),
        "PNBSMS" to ("PNB" to "Punjab National Bank"), "BOIIND" to ("BOI" to "Bank of India"),
        "UNIONB" to ("UNION" to "Union Bank of India"), "YESBNK" to ("YES" to "Yes Bank"),
        "IOBCHN" to ("IOB" to "Indian Overseas Bank"), "CBSSBI" to ("SBI" to "State Bank of India"),
    )

    fun parserFor(token: String): BankParser? = parsers.firstOrNull { token in it.senderTokens }

    fun genericBankFor(token: String): Pair<String, String>? = genericBanks[token]

    fun displayName(code: String): String =
        parsers.firstOrNull { it.bankCode == code }?.bankName
            ?: genericBanks.values.firstOrNull { it.first == code }?.second
            ?: code

    /** All tokens, for filtering the SMS inbox at query level. */
    val allTokens: Set<String> get() = parsers.flatMap { it.senderTokens }.toSet() + genericBanks.keys
}

/**
 * The parsing pipeline (ADR-006): input cap → sender routing → global gate/rejects → bank rejects → bank templates →
 * generic fallback. Wrapped so it NEVER throws: any failure becomes Unrecognized(PARSER_ERROR).
 */
object TransactionParser {
    const val VERSION = 1
    private const val MAX_BODY = 1_000

    fun parse(sms: RawSms): ParseResult = try {
        parseUnsafe(sms.copy(body = sms.body.take(MAX_BODY)))
    } catch (e: Throwable) {
        ParseResult.Unrecognized(SenderIds.token(sms.sender)?.let { BankRegistry.parserFor(it)?.bankCode }, UnrecognizedCause.PARSER_ERROR)
    }

    private fun parseUnsafe(sms: RawSms): ParseResult {
        val token = SenderIds.token(sms.sender) ?: return ParseResult.NotTransaction(RejectKind.NOT_FINANCIAL)
        val bank = BankRegistry.parserFor(token)
        val generic = if (bank == null) BankRegistry.genericBankFor(token) else null
        if (bank == null && generic == null) return ParseResult.NotTransaction(RejectKind.NOT_FINANCIAL)

        FinancialSmsDetector.classify(sms.body)?.let { return ParseResult.NotTransaction(it) }

        if (bank != null) {
            if (bank.rejects.any { it.containsMatchIn(sms.body) }) return ParseResult.NotTransaction(RejectKind.NOT_FINANCIAL)
            for (t in bank.templates) {
                val m = t.regex.find(sms.body) ?: continue
                val txn = t.map(m, sms) ?: continue
                return scored(txn, sms, parserId = bank.bankCode, templateId = t.id, verified = t.verified, isGeneric = false)
            }
        }
        val code = bank?.bankCode ?: generic!!.first
        val txn = GenericParser.parse(code, sms) ?: return ParseResult.Unrecognized(code, UnrecognizedCause.NO_TEMPLATE)
        return scored(txn, sms, parserId = "generic", templateId = null, verified = false, isGeneric = true)
    }

    /** Fixed rule table (ADR-006 §Confidence) — no numeric weights. */
    private fun scored(txn: ParsedTxn, sms: RawSms, parserId: String, templateId: String?, verified: Boolean, isGeneric: Boolean): ParseResult.Parsed {
        val reasons = mutableSetOf<Reason>()
        if (!verified) reasons += Reason.UNVERIFIED_TEMPLATE
        if (isGeneric) reasons += Reason.GENERIC_PARSER
        if (txn.merchant == null && txn.kind == TxnKind.EXPENSE) reasons += Reason.MERCHANT_MISSING
        if (txn.kind == TxnKind.UNKNOWN) reasons += Reason.TYPE_UNKNOWN
        if (txn.last4 == null) reasons += Reason.NO_ACCOUNT
        if (txn.date == null) {
            reasons += Reason.NO_DATE
        } else {
            val sentDay = IndiaTime.localDate(sms.sentAt).toEpochDay()
            if (abs(txn.date.toEpochDay() - sentDay) > 3) reasons += Reason.DATE_FAR_FROM_SENT
        }
        val confidence = when {
            txn.amountPaise == null || txn.direction == null -> Confidence.LOW
            reasons.isEmpty() -> Confidence.HIGH
            else -> Confidence.NEEDS_REVIEW
        }
        return ParseResult.Parsed(txn, confidence, reasons, parserId, templateId)
    }
}
