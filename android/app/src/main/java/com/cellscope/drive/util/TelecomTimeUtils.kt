package com.cellscope.drive.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Authoritative telecom time utility for CellScope.
 *
 * Guarantees:
 * 1. Single timestamp convention for storage: UTC instant (epoch milliseconds or UTC ISO 8601).
 * 2. User-facing presentation: Asia/Dhaka (Bangladesh Standard Time, UTC+06:00).
 * 3. CSV exports: ISO 8601 with explicit timezone offset (+06:00) converted deterministically.
 * 4. Never appends +06:00 without converting. Never double-converts.
 * 5. Elapsed duration calculations remain strictly independent of wall-clock timezone.
 */
object TelecomTimeUtils {

    val ASIA_DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
    val UTC_ZONE: ZoneId = ZoneId.of("UTC")

    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .withZone(UTC_ZONE)

    private val ISO_BDT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    private val BDT_TIME_MILLIS_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    private val BDT_TIME_SECONDS_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    private val BDT_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    private val BDT_PRETTY_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    private val BDT_FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ASIA_DHAKA_ZONE)

    /**
     * Formats an epoch millisecond instant into UTC ISO 8601 string (for internal persistence).
     * Example: 1792260000000 -> "2026-10-17T18:00:00.000Z"
     */
    fun formatIsoUtc(epochMs: Long): String {
        return ISO_UTC_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    /**
     * Formats an epoch millisecond instant into Asia/Dhaka ISO 8601 string with explicit offset.
     * Example: 1792260000000 -> "2026-10-18T00:00:00.000+06:00"
     */
    fun formatIsoBdt(epochMs: Long): String {
        return ISO_BDT_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    /**
     * Formats an epoch millisecond instant for CSV export.
     * Uses explicit ISO 8601 offset (+06:00) with UTC-to-Dhaka conversion.
     */
    fun formatCsvTimestamp(epochMs: Long, fallbackIso: String? = null): String {
        if (epochMs > 0L) {
            return formatIsoBdt(epochMs)
        }
        if (!fallbackIso.isNullOrBlank()) {
            val parsedMs = parseIsoToEpochMs(fallbackIso)
            if (parsedMs != null) {
                return formatIsoBdt(parsedMs)
            }
            return fallbackIso
        }
        return formatIsoBdt(System.currentTimeMillis())
    }

    /**
     * Formats an epoch millisecond instant for filenames in Asia/Dhaka local time.
     * Example: "20261018_143000"
     */
    fun formatBdtFilename(epochMs: Long): String {
        return BDT_FILENAME_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    /**
     * Formats epoch millisecond instant into time-only string in Asia/Dhaka timezone.
     * Example: "14:30:15.123" or "14:30:15"
     */
    fun formatBdtTimeOnly(epochMs: Long, includeMillis: Boolean = false): String {
        val instant = Instant.ofEpochMilli(epochMs)
        val formatter = if (includeMillis) BDT_TIME_MILLIS_FORMATTER else BDT_TIME_SECONDS_FORMATTER
        return formatter.format(instant)
    }

    /**
     * Formats epoch millisecond instant into standard datetime string in Asia/Dhaka timezone.
     * Example: "2026-10-18 14:30:15"
     */
    fun formatBdtDateTime(epochMs: Long): String {
        return BDT_DATE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    /**
     * Formats epoch millisecond instant into user-friendly summary string in Asia/Dhaka timezone.
     * Example: "Oct 18, 2026 14:30:15"
     */
    fun formatBdtPretty(epochMs: Long): String {
        return BDT_PRETTY_FORMATTER.format(Instant.ofEpochMilli(epochMs))
    }

    /**
     * Converts an ISO 8601 string (either with UTC 'Z' or offset like '+06:00') into
     * an Asia/Dhaka local time string for UI display (timeline, inspection, cards).
     *
     * Example: "2026-10-18T02:23:45.157Z" -> "08:23:45.157" (proper +6h conversion)
     * Example: "2026-10-18T08:23:45.157+06:00" -> "08:23:45.157" (no double conversion)
     */
    fun isoToBdtTimeOnly(isoString: String?, includeMillis: Boolean = true): String {
        if (isoString.isNullOrBlank()) return "--:--:--"
        val epochMs = parseIsoToEpochMs(isoString)
        if (epochMs != null) {
            return formatBdtTimeOnly(epochMs, includeMillis)
        }
        // Fallback for non-standard inputs
        return isoString.substringAfter("T").substringBefore("Z")
    }

    /**
     * Deterministically parses any valid ISO 8601 string into epoch milliseconds.
     * Handles 'Z', numeric offsets (+06:00, -05:00), or fallback formats.
     */
    fun parseIsoToEpochMs(isoString: String): Long? {
        val trimmed = isoString.trim()
        if (trimmed.isEmpty()) return null

        try {
            // Standard Instant parse handles ISO-8601 with Z or offsets
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            // Try OffsetDateTime for non-Z offsets
            try {
                return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // If it lacks timezone information (e.g. "2026-10-18T08:23:45.157"), assume BDT local time
                try {
                    val localDateTime = java.time.LocalDateTime.parse(trimmed)
                    return localDateTime.atZone(ASIA_DHAKA_ZONE).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    return null
                }
            }
        }
    }

    /**
     * Formats an elapsed duration in seconds into MM:SS or HH:MM:SS.
     * Independent of timezones and wall-clock time shifts.
     */
    fun formatElapsedDuration(elapsedSeconds: Long): String {
        val totalSec = kotlin.math.max(0L, elapsedSeconds)
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    /**
     * Formats an elapsed duration with detailed breakdown for reports.
     * Example: "05:42 (342 seconds)"
     */
    fun formatDurationDetailed(elapsedSeconds: Long): String {
        val totalSec = kotlin.math.max(0L, elapsedSeconds)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format(Locale.US, "%02d:%02d (%d seconds)", mins, secs, totalSec)
    }
}
