package com.spendwise.app.transaction.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.SpendWiseDatabase
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.Confidence
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.CreateAccountResult
import com.spendwise.app.domain.EntryInput
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.domain.TransactionLedger
import com.spendwise.app.transaction.parsing.RawSms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Phase 5 ingestion (ADR-004 dedup, ADR-005): staged SMS → PENDING, exactly once on every path, never counted until
 * confirmed, unknown accounts never auto-created. Bodies are anonymized (fake digits, fake names).
 */
@RunWith(AndroidJUnit4::class)
class IngestProcessorTest {

    private lateinit var db: SpendWiseDatabase
    private val day = LocalDateTime.of(2026, 10, 7, 12, 0).atZone(IndiaTime.ZONE).toInstant().toEpochMilli()
    private var now = day - 24 * 3_600_000L // accounts (and their opening anchors) are created the day before
    private val clock = Clock { now }
    private lateinit var accounts: AccountLedger
    private lateinit var ledger: TransactionLedger
    private lateinit var stager: MessageStager
    private lateinit var processor: IngestProcessor
    private lateinit var detected: DetectedAccounts
    private lateinit var repo: AccountRepository
    private var indian = 0L
    private var hdfc = 0L
    private var cash = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java)
            .addCallback(SpendWiseDatabase.SetupCallback()).allowMainThreadQueries().build()
        accounts = AccountLedger(db, clock)
        ledger = TransactionLedger(db, clock)
        stager = MessageStager(db.rawMessageDao())
        processor = IngestProcessor(db, clock, ledger)
        detected = DetectedAccounts(db, accounts, processor, clock)
        repo = AccountRepository(db.accountDao(), db.ledgerDao())
        indian = created(accounts.createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000)))
        hdfc = created(accounts.createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000)))
        cash = created(accounts.createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 0)))
        now = day + 3_600_000L
    }

    @After fun tearDown() = db.close()

    private fun created(r: CreateAccountResult) = (r as CreateAccountResult.Created).accountId
    private suspend fun balance(id: Long) = repo.observeBalance(id).first().displayedPaise
    private suspend fun month(id: Long) = repo.observeThisMonth(id, now).first()
    private suspend fun pending(id: Long) = db.reviewDao().observePending(id).first()

    private fun ibDebit(rupees: String, rrn: String, last4: String = "0000", payee: String = "Lakshmi Store") =
        "Sent Rs.$rupees from A/c *$last4 on 07-10-26 to $payee.RRN $rrn.Avl Bal Rs.2568.44.Not you?SMS BLOCK to 9289592895-Indian Bank"

    private fun hdfcDebit(rupees: String, ref: String, payee: String = "Mr A Kumar") =
        "Sent Rs.$rupees\nFrom HDFC Bank A/C *1111\nTo $payee\nOn 07/10/26\nRef $ref\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"

    private suspend fun ingest(sender: String, body: String, sentAt: Long = day, source: MessageSource = MessageSource.SMS_LIVE): IngestSummary {
        stager.stage(listOf(RawSms(sender, body, sentAt)), source, sentAt + 2_000)
        return processor.drain()
    }

    @Test fun `a bank SMS becomes ONE pending transaction that counts only once confirmed`() = runTest {
        val s = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000006"))
        assertThat(s.created).hasSize(1)
        val t = db.txnDao().getById(s.created.single())!!
        assertThat(t.status).isEqualTo(TxnStatus.PENDING)
        assertThat(t.confidence).isEqualTo(Confidence.HIGH)
        assertThat(t.accountId).isEqualTo(indian)
        assertThat(t.amountPaise).isEqualTo(-2_000)
        assertThat(t.kind).isEqualTo(TxnKind.EXPENSE)
        assertThat(t.bankRef).isEqualTo("600000000006")
        assertThat(t.reportedBalancePaise).isEqualTo(256_844)
        assertThat(t.paymentMethod).isEqualTo(PaymentMethod.UPI)
        assertThat(balance(indian)).isEqualTo(1_000_000) // pending never affects a balance
        assertThat(month(indian).spentPaise).isEqualTo(0)

        assertThat(ledger.confirm(t.id, 11)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(balance(indian)).isEqualTo(998_000)
        assertThat(month(indian).spentPaise).isEqualTo(2_000)
        assertThat(balance(hdfc)).isEqualTo(5_000_000) // isolation: other accounts untouched
        // The correction was learned for every account.
        assertThat(ledger.suggestCategory(hdfc, "Lakshmi Store")).isEqualTo(11)
    }

    @Test fun `the same SMS from the receiver, the inbox and a re-import produces exactly one record`() = runTest {
        val body = ibDebit("450.00", "600000000010", payee = "Swiggy")
        ingest("VM-INDBNK-S", body, source = MessageSource.SMS_LIVE)
        ingest("VM-INDBNK-S", body.replace(" ", "  "), source = MessageSource.SMS_INBOX) // same fingerprint (whitespace)
        ingest("JD-INDBNK-S", body, sentAt = day + 1_000, source = MessageSource.SMS_INBOX) // new fingerprint, same RRN
        val rows = pending(indian)
        assertThat(rows).hasSize(1)
        assertThat(db.reviewDao().sourceCount(rows.single().id)).isEqualTo(2)
    }

    @Test fun `two real identical teas are two transactions, not a duplicate`() = runTest {
        ingest("VM-INDBNK-S", ibDebit("20.00", "600000000021", payee = "Tea Stall"))
        ingest("VM-INDBNK-S", ibDebit("20.00", "600000000022", payee = "Tea Stall"), sentAt = day + 60_000)
        val rows = pending(indian)
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.possibleDupOfId }).containsExactly(null, null)
    }

    @Test fun `an SMS for an expense the user already typed in attaches as evidence instead of doubling it`() = runTest {
        val manual = (ledger.addEntry(EntryInput(indian, TxnKind.EXPENSE, 45_000, 10, "Swiggy", null, day - 5 * 60_000, PaymentMethod.UPI)) as LedgerResult.Ok).id
        val s = ingest("VM-INDBNK-S", ibDebit("450.00", "600000000030", payee = "Swiggy"))
        assertThat(s.created).isEmpty()
        assertThat(s.matchedManual).containsExactly(manual)
        assertThat(db.txnDao().getById(manual)!!.bankRef).isEqualTo("600000000030")
        assertThat(balance(indian)).isEqualTo(955_000)
        // And the receiver delivering it again changes nothing.
        ingest("VM-INDBNK-S", ibDebit("450.00", "600000000030", payee = "Swiggy"), sentAt = day + 5_000)
        assertThat(pending(indian)).isEmpty()
        assertThat(balance(indian)).isEqualTo(955_000)
    }

    @Test fun `two detected transactions without references are flagged, never merged`() = runTest {
        val noRef = "Rs.300.00 debited from A/c *0000 on 07-10-26 at Metro. Avl Bal Rs.900.00-Indian Bank"
        ingest("VM-INDBNK-S", noRef)
        ingest("VM-INDBNK-S", noRef.replace("Avl Bal Rs.900.00", "Avl Bal Rs.600.00"), sentAt = day + 120_000)
        val rows = pending(indian).sortedBy { it.id }
        assertThat(rows).hasSize(2)
        assertThat(rows[1].possibleDupOfId).isEqualTo(rows[0].id)
        assertThat(rows[1].confidence).isNotEqualTo(Confidence.HIGH)
        // "Confirm all" never confirms a possible duplicate.
        ledger.confirmAllHighConfidence(indian)
        assertThat(db.txnDao().getById(rows[1].id)!!.status).isEqualTo(TxnStatus.PENDING)
    }

    @Test fun `an unknown account is never created — Not mine is remembered and its text dropped`() = runTest {
        val s = ingest("VM-INDBNK-S", ibDebit("99.00", "600000000040", last4 = "9999"))
        assertThat(s.unknownAccount).isEqualTo(1)
        assertThat(db.accountDao().findByIdentifier("INDBNK", "9999")).isNull()
        val d = detected.detected.first().single()
        assertThat(d.bankCode).isEqualTo("INDBNK")
        assertThat(d.last4).isEqualTo("9999")

        detected.markNotMine("INDBNK", "9999")
        assertThat(detected.detected.first()).isEmpty()
        ingest("VM-INDBNK-S", ibDebit("15.00", "600000000041", last4 = "9999"), sentAt = day + 60_000)
        assertThat(detected.detected.first()).isEmpty()
        assertThat(db.rawMessageDao().observeUnreadable().first()).isEmpty()
        for (id in listOf(indian, hdfc, cash)) assertThat(pending(id)).isEmpty()
    }

    @Test fun `adding a detected account turns its waiting messages into pending rows in THAT account only`() = runTest {
        ingest("VM-INDBNK-S", ibDebit("99.00", "600000000050", last4 = "2222"))
        val r = detected.add("INDBNK", "2222", "Joint", AccountType.SAVINGS, 300_000)
        val id = (r as CreateAccountResult.Created).accountId
        assertThat(pending(id)).hasSize(1)
        assertThat(pending(indian)).isEmpty()
        assertThat(balance(id)).isEqualTo(300_000)
        assertThat(detected.detected.first()).isEmpty()
    }

    @Test fun `non-financial messages keep no text, unreadable ones go to Couldn't read`() = runTest {
        ingest("VM-INDBNK-S", "123456 is your OTP for txn of Rs.500.00 at Amazon. Do not share. -Indian Bank")
        ingest("VM-INDBNK-S", "Rs.250.00 has been processed on your A/c *0000. Ref 600000000501", sentAt = day + 1_000)
        val unreadable = db.rawMessageDao().observeUnreadable().first()
        assertThat(unreadable).hasSize(1)
        assertThat(unreadable.single().parseStatus).isEqualTo(ParseStatus.FINANCIAL_UNPARSED)
        assertThat(pending(indian)).isEmpty()
        // Unknown senders (and personal numbers) never even get staged.
        assertThat(stager.stage(listOf(RawSms("+919876543210", ibDebit("1.00", "600000000502"), day)), MessageSource.SMS_LIVE, day)).isEqualTo(0)
        assertThat(stager.stage(listOf(RawSms("VM-RANDOM-S", ibDebit("1.00", "600000000503"), day)), MessageSource.SMS_LIVE, day)).isEqualTo(0)
    }

    @Test fun `ignoring keeps the row so the same transaction never comes back`() = runTest {
        val id = ingest("AD-HDFCBK-T", hdfcDebit("350.00", "600000000101")).created.single()
        assertThat(ledger.ignore(id)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(db.rawMessageDao().sourcesFor(id).single().body).isNull()
        ingest("AD-HDFCBK-T", hdfcDebit("350.00", "600000000101"), sentAt = day + 9_000, source = MessageSource.SMS_INBOX)
        assertThat(pending(hdfc)).isEmpty()
        assertThat(balance(hdfc)).isEqualTo(5_000_000)
        assertThat(ledger.confirm(id)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `confirming an ATM withdrawal moves cash into the wallet and is never spending`() = runTest {
        val id = ingest("AD-HDFCBK-S", "Rs.5000.00 withdrawn from HDFC Bank A/c XX1111 on 07-10-26 at ATM KORAMANGALA").created.single()
        assertThat(db.txnDao().getById(id)!!.kind).isEqualTo(TxnKind.ATM_WITHDRAWAL)
        ledger.confirm(id)
        assertThat(balance(hdfc)).isEqualTo(4_500_000)
        assertThat(balance(cash)).isEqualTo(500_000)
        assertThat(month(hdfc).spentPaise).isEqualTo(0)
        assertThat(month(cash).incomePaise).isEqualTo(0)
        assertThat(db.txnDao().transferFor(id)).isNotNull()
    }

    @Test fun `a shared reference suggests a transfer, which the user pairs — never automatic`() = runTest {
        val out = ingest("AD-HDFCBK-T", hdfcDebit("1000.00", "600000000900", payee = "Mr K Test")).created.single()
        val into = ingest(
            "VM-INDBNK-S",
            "Rs.1000.00 credited to A/c *0000 on 07-10-26 by Mr K Test. RRN 600000000900. Avl Bal Rs.3068.44-Indian Bank",
            sentAt = day + 3 * 3_600_000L, // hours apart: only the reference links them
        ).created.single()
        assertThat(db.txnDao().transferFor(out)).isNull() // nothing paired on its own
        assertThat(ledger.transferSuggestions(out).map { it.id }).containsExactly(into)

        assertThat(ledger.pairAsTransfer(out, into)).isInstanceOf(LedgerResult.Ok::class.java)
        assertThat(db.txnDao().getById(out)!!.kind).isEqualTo(TxnKind.TRANSFER_OUT)
        assertThat(db.txnDao().getById(into)!!.kind).isEqualTo(TxnKind.TRANSFER_IN)
        assertThat(balance(hdfc)).isEqualTo(4_900_000)
        assertThat(balance(indian)).isEqualTo(1_100_000)
        for (id in listOf(hdfc, indian)) {
            assertThat(month(id).spentPaise).isEqualTo(0)
            assertThat(month(id).incomePaise).isEqualTo(0)
        }
        assertThat(ledger.pairAsTransfer(out, into)).isInstanceOf(LedgerResult.Rejected::class.java)
    }

    @Test fun `an SMS landing just after a balance the user entered is never high confidence`() = runTest {
        now = day - 10 * 60_000
        accounts.setBalance(indian, 2_000_000, com.spendwise.app.data.db.entity.AnchorSource.MANUAL)
        val id = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000060")).created.single()
        assertThat(db.txnDao().getById(id)!!.confidence).isEqualTo(Confidence.NEEDS_REVIEW)
    }

    @Test fun `confirm all touches only the selected account`() = runTest {
        ingest("VM-INDBNK-S", ibDebit("20.00", "600000000070"))
        ingest("AD-HDFCBK-T", hdfcDebit("350.00", "600000000071"), sentAt = day + 1_000)
        assertThat(ledger.confirmAllHighConfidence(indian)).isEqualTo(1)
        assertThat(pending(indian)).isEmpty()
        assertThat(pending(hdfc)).hasSize(1)
        assertThat(balance(hdfc)).isEqualTo(5_000_000)
    }

    // ------------------------------------------------------------------ audit fixes (Phase 5 review)

    @Test fun `a late-evening debit whose SMS arrives after midnight is not hidden behind that evening's balance`() = runTest {
        now = day + 6 * 3_600_000L // 7 Oct 18:00 — the user reconciles
        accounts.setBalance(indian, 2_000_000, com.spendwise.app.data.db.entity.AnchorSource.RECONCILE)
        val sentAt = day + 12 * 3_600_000L + 5 * 60_000 // 8 Oct 00:05, body still says 07-10-26
        now = sentAt + 60_000
        val id = ingest("VM-INDBNK-S", ibDebit("2000.00", "600000000080"), sentAt = sentAt).created.single()
        val t = db.txnDao().getById(id)!!
        assertThat(t.localEpochDay).isEqualTo(java.time.LocalDate.of(2026, 10, 7).toEpochDay())
        assertThat(t.confidence).isEqualTo(Confidence.NEEDS_REVIEW) // the reconcile may already include it
        ledger.confirm(id)
        assertThat(balance(indian)).isEqualTo(1_800_000) // counted after the anchor, not swallowed by it
    }

    @Test fun `a reversal moves money back the other way`() = runTest {
        val id = ingest("AD-HDFCBK-S", "Rs.500.00 debited from A/c XX1111 on 07-10-26 has been reversed. Ref 600000000301").created.single()
        val t = db.txnDao().getById(id)!!
        assertThat(t.kind).isEqualTo(TxnKind.REVERSAL)
        assertThat(t.amountPaise).isEqualTo(50_000)
        assertThat(t.confidence).isNotEqualTo(Confidence.HIGH)
        ledger.confirm(id)
        assertThat(balance(hdfc)).isEqualTo(5_050_000)
    }

    @Test fun `one side of a possible self-transfer is never confirmed as spending by confirm-all`() = runTest {
        val out = ingest("AD-HDFCBK-T", hdfcDebit("1000.00", "600000000901", payee = "Mr K Test")).created.single()
        assertThat(db.txnDao().getById(out)!!.confidence).isEqualTo(Confidence.HIGH)
        ingest(
            "VM-INDBNK-S",
            "Rs.1000.00 credited to A/c *0000 on 07-10-26 by Mr K Test. RRN 600000000901. Avl Bal Rs.3068.44-Indian Bank",
            sentAt = day + 60_000,
        )
        assertThat(db.txnDao().getById(out)!!.confidence).isEqualTo(Confidence.NEEDS_REVIEW)
        assertThat(ledger.confirmAllHighConfidence(hdfc)).isEqualTo(0)
        assertThat(month(hdfc).spentPaise).isEqualTo(0)
    }

    @Test fun `an ATM withdrawal can't be confirmed without a Cash wallet`() = runTest {
        accounts.archive(cash)
        val id = ingest("AD-HDFCBK-S", "Rs.5000.00 withdrawn from HDFC Bank A/c XX1111 on 07-10-26 at ATM KORAMANGALA").created.single()
        assertThat(ledger.confirm(id)).isInstanceOf(LedgerResult.Rejected::class.java)
        assertThat(balance(hdfc)).isEqualTo(5_000_000)
        assertThat(db.txnDao().getById(id)!!.status).isEqualTo(TxnStatus.PENDING)
    }

    @Test fun `a debit card number never invents an account, and is not guessed when another account exists`() = runTest {
        val card = "Spent Rs.800.00 On HDFC Bank Card 9999 At AMAZON On 2026-10-07:12:00:10 Not You? Call 18002586161"
        val id = ingest("AX-HDFCBK-S", card).created.single()
        assertThat(db.txnDao().getById(id)!!.accountId).isEqualTo(hdfc) // the only HDFC account
        assertThat(db.txnDao().getById(id)!!.confidence).isEqualTo(Confidence.NEEDS_REVIEW)
        assertThat(detected.detected.first()).isEmpty()

        // Once another HDFC account is known (here: marked Not mine), a digit-less SMS is no longer assigned.
        detected.markNotMine("HDFCBK", "5678")
        val s = ingest("AX-HDFCBK-S", card.replace("800.00", "801.00"), sentAt = day + 60_000)
        assertThat(s.created).isEmpty()
        assertThat(s.unreadable).isEqualTo(1)
    }

    @Test fun `an SMS for an archived account never creates a hidden row`() = runTest {
        accounts.archive(indian)
        val s = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000090"))
        assertThat(s.created).isEmpty()
        assertThat(db.rawMessageDao().observeUnreadable().first()).hasSize(1)
    }

    @Test fun `the same reference with a different amount is reviewed, not swallowed`() = runTest {
        val first = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000095")).created.single()
        val second = ingest("VM-INDBNK-S", ibDebit("5.90", "600000000095"), sentAt = day + 60_000).created.single()
        assertThat(db.txnDao().getById(second)!!.possibleDupOfId).isEqualTo(first)
        assertThat(db.txnDao().getById(second)!!.confidence).isEqualTo(Confidence.NEEDS_REVIEW)
    }

    @Test fun `a repeat of an ignored transaction keeps no text either`() = runTest {
        val id = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000096")).created.single()
        ledger.ignore(id)
        ingest("JD-INDBNK-S", ibDebit("20.00", "600000000096"), sentAt = day + 7_000)
        val sources = db.rawMessageDao().sourcesFor(id)
        assertThat(sources).hasSize(2)
        assertThat(sources.map { it.body }).containsExactly(null, null)
    }

    @Test fun `pending counts and lists never include another account's rows`() = runTest {
        ingest("VM-INDBNK-S", ibDebit("20.00", "600000000097"))
        ingest("AD-HDFCBK-T", hdfcDebit("350.00", "600000000098"), sentAt = day + 1_000)
        ingest("AD-HDFCBK-T", hdfcDebit("351.00", "600000000099"), sentAt = day + 2_000)
        assertThat(db.reviewDao().observePendingCount(indian).first()).isEqualTo(1)
        assertThat(db.reviewDao().observePendingCount(hdfc).first()).isEqualTo(2)
        assertThat(pending(indian).map { it.accountId }.toSet()).containsExactly(indian)
        for (row in pending(hdfc)) {
            assertThat(ledger.transferSuggestions(row.id).map { it.accountId }).doesNotContain(hdfc)
        }
        val perAccount = db.createdPerAccount(pending(indian).map { it.id } + pending(hdfc).map { it.id })
        assertThat(perAccount.associate { it.first.id to it.second.size }).containsExactly(indian, 1, hdfc, 2)
    }

    @Test fun `the original SMS is shown for a pending row, only for its own account, and gone once ignored`() = runTest {
        val ib = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000110")).created.single()
        ingest("AD-HDFCBK-T", hdfcDebit("350.00", "600000000111"), sentAt = day + 1_000)
        val texts = db.reviewDao().observePendingSmsText(indian).first()
        assertThat(texts.map { it.txnId }).containsExactly(ib)
        assertThat(texts.single().body).contains("Lakshmi Store")
        ledger.ignore(ib)
        assertThat(db.reviewDao().observePendingSmsText(indian).first()).isEmpty()
    }

    @Test fun `a category the user sets is remembered for that merchant — next SMS and waiting ones too`() = runTest {
        val first = ingest("VM-INDBNK-S", ibDebit("20.00", "600000000120", payee = "Kumar Tiffins")).created.single()
        val waiting = ingest("AD-HDFCBK-T", hdfcDebit("60.00", "600000000121", payee = "KUMAR TIFFINS"), sentAt = day + 1_000).created.single()
        ledger.confirm(first, 10) // the user picks Food & Dining
        // The one still waiting (another account, same merchant) takes it now…
        assertThat(db.txnDao().getById(waiting)!!.categoryId).isEqualTo(10)
        // …and the next payment arrives already categorised.
        val next = ingest("VM-INDBNK-S", ibDebit("35.00", "600000000122", payee = "Kumar Tiffins"), sentAt = day + 2_000).created.single()
        assertThat(db.txnDao().getById(next)!!.categoryId).isEqualTo(10)
        // A later correction wins over the first one.
        ledger.confirm(next, 11)
        val after = ingest("VM-INDBNK-S", ibDebit("40.00", "600000000123", payee = "Kumar Tiffins"), sentAt = day + 3_000).created.single()
        assertThat(db.txnDao().getById(after)!!.categoryId).isEqualTo(11)
        // Confirmed history is never rewritten.
        assertThat(db.txnDao().getById(first)!!.categoryId).isEqualTo(10)
    }

    @Test fun `the dictionary suggests a category for a first-time merchant`() = runTest {
        val id = ingest("VM-INDBNK-S", ibDebit("120.00", "600000000124", payee = "SRI MURUGAN MEDICAL STORE")).created.single()
        assertThat(db.txnDao().getById(id)!!.categoryId).isEqualTo(19) // Health
    }

    @Test fun `fingerprints ignore whitespace and secrets and links are redacted before storage`() {
        assertThat(Fingerprint.of("VM-INDBNK-S", day, day, "a  b\nc")).isEqualTo(Fingerprint.of("JD-INDBNK-T", day + 400, day, "a b c"))
        assertThat(Fingerprint.of("VM-INDBNK-S", day, day, "a b")).isNotEqualTo(Fingerprint.of("VM-INDBNK-S", day + 1_000, day, "a b"))
        val r = Redactor.redact("Your OTP is 482913. Track at https://bank.example/x?id=1")
        assertThat(r).doesNotContain("482913")
        assertThat(r).doesNotContain("https://")
        assertThat(Redactor.redact(ibDebit("20.00", "600000000006"))).isEqualTo(ibDebit("20.00", "600000000006"))
    }
}
