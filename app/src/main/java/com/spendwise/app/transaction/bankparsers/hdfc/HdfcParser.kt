package com.spendwise.app.transaction.bankparsers.hdfc

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

/** HDFC Bank. Formats: docs/sms/samples/hdfc.md. Bodies are multi-line, so templates separate fields with \s+. */
object HdfcParser : BankParser {
    override val bankCode = "HDFCBK"
    override val bankName = "HDFC Bank"
    override val senderTokens = setOf("HDFCBK", "HDFCBN", "HDFCSL")

    private const val AMT = """[\d,]+(?:\.\d{1,2})?"""

    override val templates = listOf(
        // VERIFIED (real samples, 2026-10-07):
        // "Sent Rs.350.00\nFrom HDFC Bank A/C *1111\nTo Mr A Kumar\nOn 28/09/26\nRef 600000000101\nNot You?…"
        // HDFC truncates long payees ("ANANTHAM RETAIL PRIVATE L") — normalised later by Merchants.key.
        Template(
            id = "hdfc.upi.debit",
            verified = true,
            regex = Regex(
                """^Sent Rs\.(?<amt>$AMT)\s+From HDFC Bank A/C\s*[*xX]*(?<acct>\d{4})\s+To\s+(?<payee>.+?)\s+On\s+(?<date>\d{2}/\d{2}/\d{2})\s+Ref\s+(?<ref>\d{9,18})""",
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
                bankRef = m.g("ref"),
                date = DateExtractor.parse(m.g("date"), "dd/MM/yy"),
                paymentMethod = PaymentMethod.UPI,
            )
        },
        // Unverified (public patterns):
        // "Received Rs.500.00 in your HDFC Bank A/c XX1111 from VPA ravi@okaxis on 07-10-26 (UPI Ref No 600000000201)"
        // "Rs.500.00 credited to HDFC Bank A/c XX1111 on 07-10-26 from VPA ravi@okaxis (UPI 600000000201)"
        Template(
            id = "hdfc.upi.credit",
            verified = false,
            regex = Regex(
                """(?:Received|Money Received\s*-?)?\s*(?:Rs\.?|INR)\s?(?<amt>$AMT)\s+(?:credited to|in your)\s+(?:HDFC Bank\s+)?A/c\s*[*xX]*(?<acct>\d{4}).*?(?:on\s+(?<date>\d{2}-\d{2}-\d{2})).*?(?:VPA\s+(?<vpa>\S+@\S+?)[\s)])(?:.*?UPI(?:\s*Ref(?:\s*No)?)?\s*(?<ref>\d{9,18}))?""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ),
        ) { m, _ ->
            ParsedTxn(
                bankCode = bankCode,
                last4 = m.g("acct"),
                amountPaise = AmountExtractor.paise(m.g("amt")),
                direction = Direction.IN,
                kind = TxnKind.INCOME,
                merchant = m.g("vpa"),
                vpa = m.g("vpa"),
                bankRef = m.g("ref"),
                date = DateExtractor.parse(m.g("date"), "dd-MM-yy"),
                paymentMethod = PaymentMethod.UPI,
            )
        },
        // "Spent Rs.1,250.00 On HDFC Bank Card 1234 At AMAZON On 2026-10-07:14:32:10 …"
        Template(
            id = "hdfc.card.spend",
            verified = false,
            regex = Regex(
                """Spent\s+(?:Rs\.?|INR)\s?(?<amt>$AMT)\s+On\s+HDFC Bank\s+(?<ctype>Credit\s+|Debit\s+)?Card\s*[*xX]*(?<acct>\d{4})\s+At\s+(?<merchant>.+?)\s+On\s+(?<date>\d{4}-\d{2}-\d{2})""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ),
        ) { m, _ ->
            ParsedTxn(
                bankCode = bankCode,
                // Debit-card digits aren't the savings account's digits: leave the account to review (ADR-005 §7).
                last4 = m.g("acct").takeIf { m.g("ctype")?.trim().equals("Credit", ignoreCase = true) },
                amountPaise = AmountExtractor.paise(m.g("amt")),
                direction = Direction.OUT,
                kind = TxnKind.EXPENSE,
                merchant = MerchantExtractor.clean(m.g("merchant")),
                date = DateExtractor.parse(m.g("date"), "yyyy-MM-dd"),
                paymentMethod = PaymentMethod.CARD,
            )
        },
        // "Rs.5000.00 withdrawn from HDFC Bank A/c XX1111 on 07-10-26 at ATM …"
        Template(
            id = "hdfc.atm",
            verified = false,
            regex = Regex(
                """(?:Rs\.?|INR)\s?(?<amt>$AMT)\s+withdrawn\s+from\s+(?:HDFC Bank\s+)?A/c\s*[*xX]*(?<acct>\d{4})\s+on\s+(?<date>\d{2}-\d{2}-\d{2})""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ),
        ) { m, _ ->
            ParsedTxn(
                bankCode = bankCode,
                last4 = m.g("acct"),
                amountPaise = AmountExtractor.paise(m.g("amt")),
                direction = Direction.OUT,
                kind = TxnKind.ATM_WITHDRAWAL,
                date = DateExtractor.parse(m.g("date"), "dd-MM-yy"),
                paymentMethod = PaymentMethod.ATM,
            )
        },
    )
}
