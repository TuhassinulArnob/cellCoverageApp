package com.cellscope.drive

import com.cellscope.drive.util.TelecomTimeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TelecomTimeUtilsTest {

    @Test
    fun testUtcToAsiaDhakaConversion() {
        // Given an instant at 10:00:00 UTC (1792272000000 ms)
        // 10:00 UTC + 6 hours = 16:00:00 BDT
        val utcIso = "2026-10-18T10:00:00.000Z"
        val epochMs = TelecomTimeUtils.parseIsoToEpochMs(utcIso)
        assertNotNull(epochMs)

        // Time-only conversion in Asia/Dhaka
        val bdtTime = TelecomTimeUtils.isoToBdtTimeOnly(utcIso, includeMillis = false)
        assertEquals("16:00:00", bdtTime)

        // With milliseconds
        val bdtTimeMillis = TelecomTimeUtils.isoToBdtTimeOnly(utcIso, includeMillis = true)
        assertEquals("16:00:00.000", bdtTimeMillis)

        // Full DateTime conversion
        val bdtDateTime = TelecomTimeUtils.formatBdtDateTime(epochMs!!)
        assertEquals("2026-10-18 16:00:00", bdtDateTime)
    }

    @Test
    fun testMidnightDateBoundaryConversion() {
        // Late night UTC: 2026-10-17 20:30:00 UTC
        // Adding 6 hours crosses midnight into next day: 2026-10-18 02:30:00 BDT
        val lateNightUtc = "2026-10-17T20:30:00.000Z"
        val epochMs = TelecomTimeUtils.parseIsoToEpochMs(lateNightUtc)
        assertNotNull(epochMs)

        val bdtDateTime = TelecomTimeUtils.formatBdtDateTime(epochMs!!)
        assertEquals("2026-10-18 02:30:00", bdtDateTime)

        // CSV serialization must show Oct 18 with +06:00 offset
        val csvIso = TelecomTimeUtils.formatCsvTimestamp(epochMs)
        assertEquals("2026-10-18T02:30:00.000+06:00", csvIso)

        // Another boundary: 2026-10-17 18:00:00 UTC -> 2026-10-18 00:00:00 BDT exactly at midnight
        val exactMidnightUtc = "2026-10-17T18:00:00.000Z"
        val midnightEpoch = TelecomTimeUtils.parseIsoToEpochMs(exactMidnightUtc)!!
        val midnightBdt = TelecomTimeUtils.formatBdtDateTime(midnightEpoch)
        assertEquals("2026-10-18 00:00:00", midnightBdt)
    }

    @Test
    fun testCsvTimestampSerialization() {
        // 2026-10-18 08:15:30.500 BDT corresponds to 02:15:30.500 UTC
        val utcInstant = "2026-10-18T02:15:30.500Z"
        val epochMs = TelecomTimeUtils.parseIsoToEpochMs(utcInstant)!!

        val csvSerialized = TelecomTimeUtils.formatCsvTimestamp(epochMs)
        assertEquals("2026-10-18T08:15:30.500+06:00", csvSerialized)

        // Ensure +06:00 is not appended to UTC clock value without converting:
        // A naive append would produce "2026-10-18T02:15:30.500+06:00", which is 6 hours behind reality!
        assertTrue("Must convert clock value to BDT before adding offset", csvSerialized.contains("08:15:30.500+06:00"))

        // Test fallback when epochMs is 0 but ISO string is provided
        val fallbackSerialized = TelecomTimeUtils.formatCsvTimestamp(0L, fallbackIso = utcInstant)
        assertEquals("2026-10-18T08:15:30.500+06:00", fallbackSerialized)

        // Test idempotency: if already in +06:00, it must NOT double-convert (+12h)
        val alreadyBdt = "2026-10-18T08:15:30.500+06:00"
        val idempotencyResult = TelecomTimeUtils.formatCsvTimestamp(0L, fallbackIso = alreadyBdt)
        assertEquals("2026-10-18T08:15:30.500+06:00", idempotencyResult)
    }

    @Test
    fun testConsistentEventTimestampsAcrossTimelineAndExport() {
        val eventEpochMs = 1792290225157L // A specific millisecond instant

        // Stored in DB
        val dbUtcIso = TelecomTimeUtils.formatIsoUtc(eventEpochMs)
        assertTrue("Storage should be in UTC Zulu", dbUtcIso.endsWith("Z"))

        // Map timeline display
        val timelineTime = TelecomTimeUtils.isoToBdtTimeOnly(dbUtcIso, includeMillis = true)

        // Direct epoch display
        val directTime = TelecomTimeUtils.formatBdtTimeOnly(eventEpochMs, includeMillis = true)

        // Export format
        val exportedIso = TelecomTimeUtils.formatCsvTimestamp(eventEpochMs)

        // Both timeline and direct must match exactly
        assertEquals(timelineTime, directTime)
        // Exported ISO must reflect the exact same hour/min/sec as the timeline
        assertTrue("CSV export time must match timeline display hour:min:sec", exportedIso.contains(timelineTime))
    }

    @Test
    fun testElapsedDurationRemainingIndependentOfWallClockTimezone() {
        // Duration = 125 seconds -> "02:05"
        val duration125 = TelecomTimeUtils.formatElapsedDuration(125L)
        assertEquals("02:05", duration125)

        // Duration = 3665 seconds -> "01:01:05" (1 hr, 1 min, 5 sec)
        val duration3665 = TelecomTimeUtils.formatElapsedDuration(3665L)
        assertEquals("01:01:05", duration3665)

        // Negative duration clamped to 00:00
        val negDuration = TelecomTimeUtils.formatElapsedDuration(-50L)
        assertEquals("00:00", negDuration)

        // Detailed duration formatting
        val detailed = TelecomTimeUtils.formatDurationDetailed(125L)
        assertEquals("02:05 (125 seconds)", detailed)

        // Verify that elapsed duration is purely monotonic/relative and independent of timezones:
        // Even across a daylight saving jump or date boundary, duration delta remains identical
        val t1 = Instant.parse("2026-10-17T23:59:00Z").toEpochMilli()
        val t2 = Instant.parse("2026-10-18T00:02:30Z").toEpochMilli()
        val deltaSeconds = (t2 - t1) / 1000
        assertEquals(210L, deltaSeconds)
        assertEquals("03:30", TelecomTimeUtils.formatElapsedDuration(deltaSeconds))
    }
}
