package com.spendwise.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Bank dates are Indian local dates. Never treat a date-only value as UTC midnight (ADR-004). */
object IndiaTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate()

    /** Days since 1970-01-01 in IST; stored on every transaction so day/week grouping is plain integer math. */
    fun localEpochDay(epochMillis: Long): Long = localDate(epochMillis).toEpochDay()

    /** Combines a date taken from an SMS body with the time-of-day the message was sent. */
    fun combine(date: LocalDate, timeSourceMillis: Long): Long {
        val time: LocalTime = Instant.ofEpochMilli(timeSourceMillis).atZone(ZONE).toLocalTime()
        return date.atTime(time).atZone(ZONE).toInstant().toEpochMilli()
    }

    fun greetingFor(epochMillis: Long): Greeting = when (Instant.ofEpochMilli(epochMillis).atZone(ZONE).hour) {
        in 5..11 -> Greeting.Morning
        in 12..16 -> Greeting.Afternoon
        in 17..21 -> Greeting.Evening
        else -> Greeting.Night
    }

    enum class Greeting(val text: String) {
        Morning("Good morning"),
        Afternoon("Good afternoon"),
        Evening("Good evening"),
        Night("Good night"),
    }
}
