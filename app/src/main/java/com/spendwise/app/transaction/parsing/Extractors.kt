package com.spendwise.app.transaction.parsing

import com.spendwise.app.core.money.Money
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/*
 * Small, pure, shared helpers (ADR-006: no extractor class hierarchy). Bank parsers call these from their
 * templates; the generic parser chains them. Nothing here throws on bad input.
 */

private const val AMT = """(?:Rs\.?|INR|₹)\s?([\d,]+(?:\.\d{1,2})?)"""

object AmountExtractor {
    private val AMOUNT = Regex(AMT, RegexOption.IGNORE_CASE)
    private val BALANCE = Regex("""(?:Avl\.?|Available|Avail\.?)\s*Bal(?:ance)?\s*(?:is|:)?\s*""" + AMT, RegexOption.IGNORE_CASE)

    /** Paise from "2,844.44" etc. (never via Double). */
    fun paise(text: String?): Long? = text?.let { Money.parseRupees(it) }

    /** The transaction amount: the first rupee amount that is not the available balance. */
    fun transactionAmount(body: String): Long? {
        val balanceRanges = BALANCE.findAll(body).map { it.range }.toList()
        return AMOUNT.findAll(body)
            .firstOrNull { m -> balanceRanges.none { m.range.first in it } }
            ?.groupValues?.get(1)?.let(::paise)
    }

    fun availableBalance(body: String): Long? = BALANCE.find(body)?.groupValues?.get(1)?.let(::paise)

    fun hasAmount(body: String) = AMOUNT.containsMatchIn(body)
}

object AccountExtractor {
    // A DEBIT card's digits are not the account's digits, so a bare/debit "Card 1234" never identifies an account
    // (it would invent a phantom account). Only a credit card is its own account.
    private val ACCOUNT = Regex(
        """(?:A/c|Acct|Account|Credit\s+Card)\s*(?:no\.?|number|ending(?:\s+with)?)?\s*[*xX.]*\s*(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Last 4 digits: "*0000", "XX0000", "ending 0000", "Credit Card 1234" → "0000". */
    fun last4(body: String): String? = ACCOUNT.find(body)?.groupValues?.get(1)

    fun normalize(raw: String?): String? = raw?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }
}

object RefExtractor {
    private val REF = Regex("""(?:RRN|UTR|UPI\s*Ref(?:\s*No)?|Ref(?:erence)?(?:\s*No)?|Txn\s*(?:ID|No))\.?\s*[:#-]?\s*(\d{9,18})""", RegexOption.IGNORE_CASE)
    fun bankRef(body: String): String? = REF.find(body)?.groupValues?.get(1)
}

object DateExtractor {
    private val PATTERNS: List<Pair<Regex, DateTimeFormatter>> = listOf(
        Regex("""\b(\d{2}-\d{2}-\d{2})\b""") to DateTimeFormatter.ofPattern("dd-MM-yy"),
        Regex("""\b(\d{2}/\d{2}/\d{2})\b""") to DateTimeFormatter.ofPattern("dd/MM/yy"),
        Regex("""\b(\d{2}-\d{2}-\d{4})\b""") to DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        Regex("""\b(\d{2}/\d{2}/\d{4})\b""") to DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        Regex("""\b(\d{4}-\d{2}-\d{2})\b""") to DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        Regex("""\b(\d{2}-[A-Za-z]{3}-\d{2})\b""") to DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
        Regex("""\b(\d{2}[A-Za-z]{3}\d{2})\b""") to DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH),
    )

    /** Parses with an explicit pattern (templates know their own format). */
    fun parse(text: String?, pattern: String): LocalDate? = text?.let {
        try {
            LocalDate.parse(it.trim(), DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Best-effort for the generic parser: first recognisable date in the body. */
    fun find(body: String): LocalDate? {
        for ((regex, fmt) in PATTERNS) {
            val m = regex.find(body) ?: continue
            try {
                return LocalDate.parse(m.groupValues[1].replaceFirstChar { c -> c }.let(::fixMonthCase), fmt)
            } catch (_: DateTimeParseException) {
                // try next pattern
            }
        }
        return null
    }

    private fun fixMonthCase(s: String) = Regex("[A-Za-z]{3}").replace(s) { it.value.lowercase().replaceFirstChar(Char::uppercase) }
}

object TransactionTypeDetector {
    private val OUT = Regex("""\b(debited|sent|spent|paid|withdrawn|purchase|deducted|debit)\b""", RegexOption.IGNORE_CASE)
    private val IN = Regex("""\b(credited|received|deposited|credit|refunded)\b""", RegexOption.IGNORE_CASE)

    /** "Rs.500 debited … has been reversed": the verb describes the ORIGINAL payment, so the money moves the other way. */
    private val REVERSED_ORIGINAL = Regex("""\b(debited|credited)\b.{0,120}?\b(has been|was|is|been)\s+reversed\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private fun word(w: String) = Regex("""\b$w\b""", RegexOption.IGNORE_CASE)
    private val REVERSAL = word("revers(?:al|ed)")
    private val REFUND = word("refund(?:ed)?")
    private val ATM = word("ATM")
    private val CASH_DEPOSIT = word("cash deposit(?:ed)?")
    private val INTEREST = word("interest")
    private val FEE = word("(?:charges?|fees?)")

    fun direction(body: String): Direction? {
        val o = OUT.find(body)?.range?.first
        val i = IN.find(body)?.range?.first
        val plain = when {
            o != null && (i == null || o < i) -> Direction.OUT
            i != null -> Direction.IN
            else -> null
        }
        return if (plain != null && REVERSED_ORIGINAL.containsMatchIn(body)) {
            if (plain == Direction.OUT) Direction.IN else Direction.OUT
        } else {
            plain
        }
    }

    // Whole words only: "treatment" must never read as an ATM withdrawal (that would move money into Cash).
    fun kind(body: String, direction: Direction?): TxnKind {
        return when {
            REVERSAL.containsMatchIn(body) -> TxnKind.REVERSAL
            REFUND.containsMatchIn(body) -> TxnKind.REFUND
            ATM.containsMatchIn(body) && direction == Direction.OUT -> TxnKind.ATM_WITHDRAWAL
            CASH_DEPOSIT.containsMatchIn(body) && direction == Direction.IN -> TxnKind.CASH_DEPOSIT
            INTEREST.containsMatchIn(body) && direction == Direction.IN -> TxnKind.INTEREST
            FEE.containsMatchIn(body) && direction == Direction.OUT -> TxnKind.FEE
            direction == Direction.OUT -> TxnKind.EXPENSE
            direction == Direction.IN -> TxnKind.INCOME
            else -> TxnKind.UNKNOWN
        }
    }

    fun method(body: String): PaymentMethod {
        val b = body.uppercase()
        return when {
            "UPI" in b || Regex("""\bRRN\b""").containsMatchIn(b) || "VPA" in b -> PaymentMethod.UPI
            "NEFT" in b -> PaymentMethod.NEFT
            "IMPS" in b -> PaymentMethod.IMPS
            "RTGS" in b -> PaymentMethod.RTGS
            "ATM" in b -> PaymentMethod.ATM
            "CARD" in b || "POS" in b -> PaymentMethod.CARD
            "NACH" in b || "AUTOPAY" in b || "MANDATE" in b -> PaymentMethod.AUTO_DEBIT
            "CHQ" in b || "CHEQUE" in b -> PaymentMethod.CHEQUE
            else -> PaymentMethod.UNKNOWN
        }
    }
}

object MerchantExtractor {
    private val VPA = Regex("""\b([a-zA-Z0-9.\-_]{2,}@[a-zA-Z]{2,})\b""")
    private val COUNTERPARTY = Regex(
        """\b(?:to|at|by|from|towards|Info:?)\s+(?!A/c|your|HDFC Bank|Indian Bank)([A-Za-z0-9][A-Za-z0-9 &.'\-*/]{1,40}?)(?=\s+on\s|\s*\.\s|\s+Ref|\s*\(|\s+RRN|\s+UPI|\s*$|\.RRN|\s+Avl)""",
        RegexOption.IGNORE_CASE,
    )

    fun vpa(body: String): String? = VPA.find(body)?.groupValues?.get(1)

    fun counterparty(body: String): String? = COUNTERPARTY.find(body)?.groupValues?.get(1)?.trim()?.trimEnd('.')

    /** "Mr Ravi K." → "Mr Ravi K" (the dot before RRN is punctuation, not part of the name). */
    fun clean(raw: String?): String? = raw?.trim()?.trimEnd('.', ',')?.replace(Regex("\\s+"), " ")?.ifEmpty { null }
}

/**
 * Gate before parsing (ADR-005 §5): non-transactions are rejected; anything that looks like money from a known
 * bank but matches no template becomes "Couldn't read" instead of being dropped.
 */
object FinancialSmsDetector {
    private val OTP = Regex("""\b(OTP|one[- ]time password|verification code|passcode)\b""", RegexOption.IGNORE_CASE)
    private val FAILED = Regex("""\b(failed|declined|unsuccessful|could not be processed|insufficient (?:funds|balance))\b""", RegexOption.IGNORE_CASE)
    private val MANDATE = Regex("""\b(will be debited|is due|due on|due date|scheduled for|upcoming|reminder|will be auto[- ]?debited)\b""", RegexOption.IGNORE_CASE)
    private val PROMO = Regex("""\b(offer|pre-?approved|apply now|eligible for|win|congratulations|limited period|click|download|loan up to)\b""", RegexOption.IGNORE_CASE)
    /** A money movement that already happened — "will be debited" is a future reminder, not a completed debit. */
    private val COMPLETED = Regex("""(?<!will be )(?<!will be auto-)\b(debited|credited|sent|received)\b""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_REF =Regex("""\bA/c\b|\bAcct\b""", RegexOption.IGNORE_CASE)
    private val VERB = Regex("""\b(debited|credited|sent|received|spent|withdrawn|deposited|paid|transferred|purchase|refund|reversed)\b""", RegexOption.IGNORE_CASE)

    fun classify(body: String): RejectKind? = when {
        OTP.containsMatchIn(body) -> RejectKind.OTP
        FAILED.containsMatchIn(body) -> RejectKind.FAILED
        MANDATE.containsMatchIn(body) && !COMPLETED.containsMatchIn(body) -> RejectKind.MANDATE_REMINDER
        PROMO.containsMatchIn(body) && !VERB.containsMatchIn(body) -> RejectKind.PROMO
        !AmountExtractor.hasAmount(body) -> RejectKind.NOT_FINANCIAL
        // Financial = amount AND (a money verb OR an account reference). An account mention without a verb we can
        // read still goes to "Couldn't read" rather than being dropped.
        !VERB.containsMatchIn(body) && !ACCOUNT_REF.containsMatchIn(body) ->
            if (Regex("""\bbal""", RegexOption.IGNORE_CASE).containsMatchIn(body)) RejectKind.BALANCE_ENQUIRY else RejectKind.NOT_FINANCIAL
        else -> null
    }
}
