package com.spendwise.app.transaction.parsing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.spendwise.app.core.time.IndiaTime
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Golden tests over every fixture in test/resources/sms/\*.sms (ADR-006 §Testing), plus the meta-rule that every
 * VERIFIED template is backed by at least one REAL sample, and a never-throw fuzz test.
 */
class ParserFixturesTest {

    data class Fixture(val meta: Map<String, String>, val body: String) {
        val id get() = meta.getValue("id")
        fun sms(): RawSms {
            val sent = LocalDateTime.parse(meta.getValue("sentAt")).atZone(IndiaTime.ZONE).toInstant().toEpochMilli()
            return RawSms(meta.getValue("sender"), body, sent)
        }
    }

    private val fixtures: List<Fixture> by lazy {
        listOf("indianbank.sms", "hdfc.sms", "other.sms").flatMap { name ->
            val text = javaClass.classLoader!!.getResource("sms/$name")!!.readText().replace("\r\n", "\n")
            text.split("\n===\n").map { block ->
                val (head, body) = block.split("\n---\n", limit = 2)
                val meta = head.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
                Fixture(meta, body.trimEnd('\n'))
            }
        }
    }

    @Test fun `every fixture parses to its expected fields`() {
        assertThat(fixtures.size).isAtLeast(20)
        for (f in fixtures) {
            val result = TransactionParser.parse(f.sms())
            val m = f.meta
            val why = "fixture ${f.id}: $result"
            when (m.getValue("expect")) {
                "PARSED" -> {
                    assertWithMessage(why).that(result).isInstanceOf(ParseResult.Parsed::class.java)
                    val p = result as ParseResult.Parsed
                    m["template"]?.let { assertWithMessage(why).that(p.templateId).isEqualTo(it) }
                    m["parser"]?.let { assertWithMessage(why).that(p.parserId).isEqualTo(it) }
                    m["amount"]?.let { assertWithMessage(why).that(p.txn.amountPaise).isEqualTo(it.toLong()) }
                    m["direction"]?.let { assertWithMessage(why).that(p.txn.direction?.name).isEqualTo(it) }
                    m["kind"]?.let { assertWithMessage(why).that(p.txn.kind.name).isEqualTo(it) }
                    m["last4"]?.let { assertWithMessage(why).that(p.txn.last4).isEqualTo(it) }
                    m["merchant"]?.let { assertWithMessage(why).that(p.txn.merchant).isEqualTo(it) }
                    m["ref"]?.let { assertWithMessage(why).that(p.txn.bankRef).isEqualTo(it) }
                    m["balance"]?.let { assertWithMessage(why).that(p.txn.availableBalancePaise).isEqualTo(it.toLong()) }
                    m["date"]?.let { assertWithMessage(why).that(p.txn.date).isEqualTo(LocalDate.parse(it)) }
                    m["confidence"]?.let { assertWithMessage(why).that(p.confidence.name).isEqualTo(it) }
                }
                "NOT_TRANSACTION" -> {
                    assertWithMessage(why).that(result).isInstanceOf(ParseResult.NotTransaction::class.java)
                    m["reject"]?.let { assertWithMessage(why).that((result as ParseResult.NotTransaction).kind.name).isEqualTo(it) }
                }
                "UNRECOGNIZED" -> assertWithMessage(why).that(result).isInstanceOf(ParseResult.Unrecognized::class.java)
                else -> error("bad expect in ${f.id}")
            }
        }
    }

    @Test fun `every verified template is backed by a real sample, and every template has a fixture`() {
        val covered = fixtures.mapNotNull { it.meta["template"] }.toSet()
        val realCovered = fixtures.filter { it.meta["origin"] == "real" }.mapNotNull { it.meta["template"] }.toSet()
        for (bank in BankRegistry.parsers) for (t in bank.templates) {
            assertWithMessage("template ${t.id} has no fixture").that(covered).contains(t.id)
            if (t.verified) assertWithMessage("verified template ${t.id} has no REAL fixture").that(realCovered).contains(t.id)
        }
    }

    @Test fun `the six consecutive Indian Bank messages form a consistent balance chain`() {
        val chain = fixtures.filter { it.id.startsWith("ib-upi-debit") }
            .map { (TransactionParser.parse(it.sms()) as ParseResult.Parsed).txn }
        assertThat(chain).hasSize(6)
        chain.zipWithNext { a, b -> assertThat(a.availableBalancePaise!! - b.amountPaise!!).isEqualTo(b.availableBalancePaise) }
    }

    @Test fun `the parser never throws and stays fast on hostile input`() {
        val rnd = Random(42)
        val seeds = fixtures.map { it.body }
        val alphabet = "Rs.0123456789*/-,:A/cRRNSentto credited debited \n₹@xX".toList()
        repeat(3_000) { i ->
            val body = when (i % 4) {
                0 -> (1..rnd.nextInt(0, 400)).map { alphabet.random(rnd) }.joinToString("")
                1 -> seeds.random(rnd).let { s -> if (s.isEmpty()) s else s.substring(0, rnd.nextInt(s.length)) }
                2 -> seeds.random(rnd).toMutableList().also { l -> repeat(5) { if (l.isNotEmpty()) l.removeAt(rnd.nextInt(l.size)) } }.joinToString("")
                else -> "Sent Rs." + "9".repeat(rnd.nextInt(1, 30)) + " from A/c *" + "1".repeat(rnd.nextInt(0, 10)) + " to " + "a.".repeat(rnd.nextInt(0, 2_000))
            }
            val start = System.nanoTime()
            val r = TransactionParser.parse(RawSms(listOf("VM-INDBNK-S", "AD-HDFCBK-T", "VM-SBIINB-S").random(rnd), body, 1_759_800_000_000))
            val ms = (System.nanoTime() - start) / 1_000_000
            assertThat(r).isNotNull()
            assertWithMessage("slow parse ($ms ms) for case $i").that(ms).isLessThan(250)
        }
    }

    @Test fun `DLT sender tokens are extracted, phone numbers are not trusted`() {
        assertThat(SenderIds.token("VM-INDBNK-S")).isEqualTo("INDBNK")
        assertThat(SenderIds.token("AD-HDFCBK-T")).isEqualTo("HDFCBK")
        assertThat(SenderIds.token("JD-HDFCBK")).isEqualTo("HDFCBK")
        assertThat(SenderIds.token("+919876543210")).isNull()
        assertThat(SenderIds.token("HDFCBK")).isNull()
    }
}
