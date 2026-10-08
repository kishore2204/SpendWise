package com.spendwise.app.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.domain.AccountLedger
import com.spendwise.app.domain.NewAccount
import com.spendwise.app.transaction.ingest.IncomingSms
import com.spendwise.app.transaction.ingest.MessageStager
import com.spendwise.app.transaction.parsing.RawSms
import com.spendwise.app.transaction.sms.IngestWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DEBUG BUILD ONLY (app/src/debug). Seeds anonymized fake bank SMS through the real pipeline — staging, the same
 * worker, parser, dedup and notifications a real SMS uses — so the SMS features can be tried on a real phone without
 * giving the debug app SMS access (ADR-002: debug holds test data only). Fake digits, fake names.
 *
 *   adb shell am broadcast -a com.spendwise.debug.SEED_SMS -n com.spendwise.app.debug/com.spendwise.app.debugtools.DebugSeedReceiver
 *
 * Only the shell can send it (the receiver requires DUMP, which apps can't hold). Each run uses fresh references,
 * so it can be repeated; re-sending the exact same messages is what the dedup tests cover.
 */
class DebugSeedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun stager(): MessageStager
        fun accounts(): AccountLedger
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val pending = goAsync()
        scope.launch {
            try {
                // The fake accounts the seed SMS belong to (no-ops if they already exist).
                deps.accounts().createAccount(NewAccount("INDBNK", "Indian Bank", "0000", "Indian", AccountType.SAVINGS, 1_000_000))
                deps.accounts().createAccount(NewAccount("HDFCBK", "HDFC Bank", "1111", "HDFC", AccountType.SAVINGS, 5_000_000))
                deps.accounts().createAccount(NewAccount("CASH", "Cash", "", "Wallet", AccountType.CASH, 0))
                val now = System.currentTimeMillis()
                deps.stager().stage(seed(now).map { (minutesAgo, sms) -> IncomingSms(sms.copy(sentAt = now - minutesAgo * 60_000L), now) }, MessageSource.SMS_LIVE)
                IngestWorker.enqueue(app)
            } finally {
                pending.finish()
            }
        }
    }

    private fun seed(now: Long): List<Pair<Int, RawSms>> {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = Instant.ofEpochMilli(now).atZone(zone)
        val dash = DateTimeFormatter.ofPattern("dd-MM-yy").format(day)
        val slash = DateTimeFormatter.ofPattern("dd/MM/yy").format(day)
        val base = 600_000_000_000L + (now / 1_000) % 100_000_000L * 10 // fresh 12-digit refs per run
        fun ref(i: Int) = (base + i).toString()
        fun ib(body: String) = RawSms("VM-INDBNK-S", body, 0)
        fun hdfc(body: String) = RawSms("AD-HDFCBK-T", body, 0)
        return listOf(
            // Verified Indian Bank UPI debits → HIGH confidence, one-tap confirm.
            95 to ib("Sent Rs.20.00 from A/c *0000 on $dash to Lakshmi Store.RRN ${ref(1)}.Avl Bal Rs.9980.00.Not you?SMS BLOCK to 9289592895-Indian Bank"),
            80 to ib("Sent Rs.450.00 from A/c *0000 on $dash to Swiggy.RRN ${ref(2)}.Avl Bal Rs.9530.00.Not you?SMS BLOCK to 9289592895-Indian Bank"),
            // Verified HDFC UPI debit (multi-line, like the real ones).
            70 to hdfc("Sent Rs.350.00\nFrom HDFC Bank A/C *1111\nTo Mr A Kumar\nOn $slash\nRef ${ref(3)}\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"),
            // ATM withdrawal → confirming it moves the cash into the Wallet (a transfer, never spending).
            60 to RawSms("AD-HDFCBK-S", "Rs.2000.00 withdrawn from HDFC Bank A/c XX1111 on $dash at ATM KORAMANGALA", 0),
            // Unverified credit format → needs review.
            50 to ib("Rs.2500.00 credited to A/c *0000 on $dash by Mr R Test. RRN ${ref(4)}. Avl Bal Rs.12030.00-Indian Bank"),
            // Own-account transfer HDFC → Indian Bank sharing one reference → "Is this a transfer?" suggestion.
            40 to hdfc("Sent Rs.1000.00\nFrom HDFC Bank A/C *1111\nTo Mr K Test\nOn $slash\nRef ${ref(5)}\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"),
            39 to ib("Rs.1000.00 credited to A/c *0000 on $dash by Mr K Test. RRN ${ref(5)}. Avl Bal Rs.13030.00-Indian Bank"),
            // An account that isn't in the app → "New account detected · Not mine / Add account".
            30 to ib("Sent Rs.99.00 from A/c *9999 on $dash to Tea Point.RRN ${ref(6)}.Avl Bal Rs.500.00.Not you?SMS BLOCK to 9289592895-Indian Bank"),
            // Not a transaction → dropped, no text kept.
            20 to ib("482913 is your OTP for txn of Rs.500.00 at Amazon. Do not share. -Indian Bank"),
            // Financial but unreadable → "Couldn't read".
            10 to ib("Rs.250.00 has been processed on your A/c *0000. Ref ${ref(7)}"),
        )
    }

    companion object {
        const val ACTION = "com.spendwise.debug.SEED_SMS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
