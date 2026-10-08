package com.spendwise.app.transaction.bankparsers.generic

import com.spendwise.app.transaction.parsing.AccountExtractor
import com.spendwise.app.transaction.parsing.AmountExtractor
import com.spendwise.app.transaction.parsing.DateExtractor
import com.spendwise.app.transaction.parsing.MerchantExtractor
import com.spendwise.app.transaction.parsing.ParsedTxn
import com.spendwise.app.transaction.parsing.RawSms
import com.spendwise.app.transaction.parsing.RefExtractor
import com.spendwise.app.transaction.parsing.TransactionTypeDetector

/**
 * Keyword + amount fallback for known banks when no template matches (a bank changed its wording, or a bank
 * without a dedicated parser). Never better than NEEDS_REVIEW (ADR-006). Returns null if not even an amount and
 * a direction can be found, so the message goes to "Couldn't read".
 */
object GenericParser {
    fun parse(bankCode: String, sms: RawSms): ParsedTxn? {
        val body = sms.body
        val amount = AmountExtractor.transactionAmount(body) ?: return null
        val direction = TransactionTypeDetector.direction(body) ?: return null
        val vpa = MerchantExtractor.vpa(body)
        return ParsedTxn(
            bankCode = bankCode,
            last4 = AccountExtractor.last4(body),
            amountPaise = amount,
            direction = direction,
            kind = TransactionTypeDetector.kind(body, direction),
            merchant = MerchantExtractor.clean(MerchantExtractor.counterparty(body)) ?: vpa,
            vpa = vpa,
            bankRef = RefExtractor.bankRef(body),
            availableBalancePaise = AmountExtractor.availableBalance(body),
            date = DateExtractor.find(body),
            paymentMethod = TransactionTypeDetector.method(body),
        )
    }
}
