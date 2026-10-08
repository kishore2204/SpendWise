package com.spendwise.app.transaction.ingest

import com.spendwise.app.data.db.dao.RawMessageDao
import com.spendwise.app.data.db.entity.MessageSource
import com.spendwise.app.data.db.entity.ParseStatus
import com.spendwise.app.data.db.entity.RawMessageEntity
import com.spendwise.app.transaction.parsing.BankRegistry
import com.spendwise.app.transaction.parsing.RawSms
import com.spendwise.app.transaction.parsing.SenderIds
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message identity (ADR-005 §2): sha256(dltToken | sentAtSeconds | whitespace-collapsed body). The broadcast's
 * `timestampMillis` equals the inbox's `date_sent`, so the same SMS has the same key on every path, and two
 * genuinely identical payment texts at different times still differ.
 */
object Fingerprint {
    fun of(sender: String, sentAt: Long, receivedAt: Long, body: String): String {
        val token = SenderIds.token(sender) ?: sender.trim().uppercase()
        // date_sent can be 0 on some paths; fall back to the receive time floored to the minute.
        val seconds = if (sentAt > 0) sentAt / 1_000 else (receivedAt / 60_000) * 60
        val normalized = body.replace(Regex("\\s+"), " ").trim()
        val digest = MessageDigest.getInstance("SHA-256").digest("$token|$seconds|$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Strips secrets and links before a body is ever stored (ADR-005 §10). Transaction fields are left intact. */
object Redactor {
    private val SECRET = Regex("""\b(OTP|CVV|PIN|passcode|password|code)\b(\D{0,20})(\d{3,8})\b""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""\b(?:https?://|www\.)\S+|\b[a-z0-9-]+\.(?:in|com|co|me|ly)/\S*""", RegexOption.IGNORE_CASE)

    fun redact(body: String): String = body
        .replace(SECRET) { m -> m.groupValues[1] + m.groupValues[2] + "•".repeat(m.groupValues[3].length) }
        .replace(URL, "[link]")
}

/** An SMS plus when this device got it (the inbox's `date`; the receiver's clock). */
data class IncomingSms(val sms: RawSms, val receivedAt: Long)

/** Allowlisted DLT senders only (ADR-005 §1 sender gate). Personal numbers and unknown senders never get in. */
object SenderGate {
    fun allows(sender: String): Boolean = SenderIds.token(sender)?.let { it in BankRegistry.allTokens } == true
}

/**
 * The single way a message enters the app — receiver, pull-to-refresh and historical import all stage here, then
 * the [IngestProcessor] drains RECEIVED rows. Staging never parses, so a parser bug can't lose a message.
 */
@Singleton
class MessageStager @Inject constructor(private val dao: RawMessageDao, private val gate: IngestGate) {

    /** Returns how many messages were NEW (not seen before on any path). */
    suspend fun stage(messages: List<RawSms>, source: MessageSource, receivedAt: Long): Int =
        stage(messages.map { IncomingSms(it, receivedAt) }, source)

    @JvmName("stageIncoming")
    suspend fun stage(messages: List<IncomingSms>, source: MessageSource): Int = gate.withLock { stageLocked(messages, source) }

    /** For callers already holding [IngestGate]. */
    suspend fun stageLocked(messages: List<IncomingSms>, source: MessageSource): Int {
        var inserted = 0
        for ((sms, receivedAt) in messages) {
            if (!SenderGate.allows(sms.sender) || sms.body.isBlank()) continue
            val row = RawMessageEntity(
                source = source,
                sender = sms.sender,
                body = Redactor.redact(sms.body),
                sentAt = if (sms.sentAt > 0) sms.sentAt else receivedAt,
                receivedAt = receivedAt,
                fingerprint = Fingerprint.of(sms.sender, sms.sentAt, receivedAt, sms.body),
                parseStatus = ParseStatus.RECEIVED,
                parserVersion = 0,
            )
            if (dao.insertOrIgnore(row) > 0) inserted++
        }
        return inserted
    }
}
