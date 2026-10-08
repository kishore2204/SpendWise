package com.spendwise.app.transaction.parsing

import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import java.time.LocalDate

/** An SMS as received (broadcast) or read (inbox). [sentAt] is the service-centre time (`date_sent`). */
data class RawSms(val sender: String, val body: String, val sentAt: Long)

enum class Direction { IN, OUT }

/** Everything a parser could extract. All fields are optional except where noted (ADR-006). */
data class ParsedTxn(
    /** Canonical bank code from the registry (e.g. INDBNK, HDFCBK). */
    val bankCode: String,
    val last4: String? = null,
    val amountPaise: Long? = null,
    val direction: Direction? = null,
    val kind: TxnKind = TxnKind.UNKNOWN,
    val merchant: String? = null,
    val vpa: String? = null,
    val bankRef: String? = null,
    val availableBalancePaise: Long? = null,
    val date: LocalDate? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
)

enum class Confidence { HIGH, NEEDS_REVIEW, LOW }

/** Why a parse was demoted — shown in review and used by tests. */
enum class Reason { UNVERIFIED_TEMPLATE, GENERIC_PARSER, MERCHANT_MISSING, TYPE_UNKNOWN, DATE_FAR_FROM_SENT, NO_ACCOUNT, NO_DATE, UNTRUSTED_SENDER }

enum class RejectKind { OTP, PROMO, FAILED, MANDATE_REMINDER, BALANCE_ENQUIRY, NOT_FINANCIAL }

enum class UnrecognizedCause { NO_TEMPLATE, PARSER_ERROR }

sealed interface ParseResult {
    data class Parsed(
        val txn: ParsedTxn,
        val confidence: Confidence,
        val reasons: Set<Reason>,
        val parserId: String,
        val templateId: String?,
    ) : ParseResult

    data class NotTransaction(val kind: RejectKind) : ParseResult

    /** Financial-looking SMS from a known bank that no template could read → "Couldn't read" (never dropped). */
    data class Unrecognized(val bankCode: String?, val cause: UnrecognizedCause) : ParseResult
}
