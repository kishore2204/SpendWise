package com.spendwise.app.transaction.bankparsers.indianbank

import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.transaction.parsing.AmountExtractor
import com.spendwise.app.transaction.parsing.BankParser
import com.spendwise.app.transaction.parsing.DateExtractor
import com.spendwise.app.transaction.parsing.Direction
import com.spendwise.app.transaction.parsing.MerchantExtractor
import com.spendwise.app.transaction.parsing.ParsedTxn
import com.spendwise.app.transaction.parsing.Template
import com.spendwise.app.transaction.parsing.g

/** Indian Bank. Formats: docs/sms/samples/indian-bank.md. */
object IndianBankParser : BankParser {
    override val bankCode = "INDBNK"
    override val bankName = "Indian Bank"
    override val senderTokens = setOf("INDBNK", "INDBNK1", "INDBANK")

    private const val AMT = """[\d,]+(?:\.\d{1,2})?"""

    override val templates = listOf(
        // VERIFIED (real samples, 2026-10-07):
        // "Sent Rs.23.00 from A/c *0000 on 07-10-26 to CUMTA.RRN 600000000001.Avl Bal Rs.2844.44.Not you?…"
        // The payee runs straight into ".RRN" — and a person's initial adds its own dot ("Mr Ravi K.RRN").
        Template(
            id = "indianbank.upi.debit",
            verified = true,
            regex = Regex(
                """^Sent Rs\.(?<amt>$AMT) from A/c \*(?<acct>\d{4}) on (?<date>\d{2}-\d{2}-\d{2}) to (?<payee>.+?)\.?\s*RRN\s*(?<rrn>\d{12})\.?\s*(?:Avl Bal Rs\.(?<bal>$AMT))?""",
                RegexOption.IGNORE_CASE,
            ),
        ) { m, _ ->
            ParsedTxn(
                bankCode = bankCode,
                last4 = m.g("acct"),
                amountPaise = AmountExtractor.paise(m.g("amt")),
                direction = Direction.OUT,
                kind = TxnKind.EXPENSE,
                merchant = MerchantExtractor.clean(m.g("payee")),
                bankRef = m.g("rrn"),
                availableBalancePaise = AmountExtractor.paise(m.g("bal")),
                date = DateExtractor.parse(m.g("date"), "dd-MM-yy"),
                paymentMethod = PaymentMethod.UPI,
            )
        },
        // Unverified (public pattern; capped at NEEDS_REVIEW until a real sample arrives):
        // "Rs.500.00 credited to A/c *0000 on 07-10-26 by Mr Ravi K. RRN 600000000003. Avl Bal Rs.3344.44"
        Template(
            id = "indianbank.credit",
            verified = false,
            regex = Regex(
                """Rs\.?\s?(?<amt>$AMT)\s+credited to (?:your\s+)?A/c\s*[*xX]*(?<acct>\d{4})\s+on\s+(?<date>\d{2}-\d{2}-\d{2})(?:\s+(?:by|from)\s+(?<payer>.+?)(?=\.?\s*(?:RRN|Ref)\b|\.\s*Avl|-Indian Bank|\s*$))?(?:\.?\s*(?:RRN|Ref)\s*(?<rrn>\d{9,18}))?(?:.*?Avl Bal Rs\.?\s?(?<bal>$AMT))?""",
                RegexOption.IGNORE_CASE,
            ),
        ) { m, sms ->
            ParsedTxn(
                bankCode = bankCode,
                last4 = m.g("acct"),
                amountPaise = AmountExtractor.paise(m.g("amt")),
                direction = Direction.IN,
                kind = TxnKind.INCOME,
                merchant = MerchantExtractor.clean(m.g("payer")),
                bankRef = m.g("rrn"),
                availableBalancePaise = AmountExtractor.paise(m.g("bal")),
                date = DateExtractor.parse(m.g("date"), "dd-MM-yy"),
                paymentMethod = com.spendwise.app.transaction.parsing.TransactionTypeDetector.method(sms.body),
            )
        },
    )
}
