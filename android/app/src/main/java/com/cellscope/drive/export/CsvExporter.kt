package com.cellscope.drive.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cellscope.drive.data.entity.DriveSession
import com.cellscope.drive.data.entity.LteMeasurementEntity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private val header = listOf(
        "Timestamp",
        "Latitude",
        "Longitude",
        "Speed (km/h)",
        "Serving Cell ID",
        "Serving Cell RSRP",
        "Serving Cell RSRQ",
        "Serving Cell CINR",
        "Handover Trigger",
        "TAC",
        "EARFCN",
        "RSSI (dBm)",
        "Network Type",
        "Operator"
    ).joinToString(",")

    fun exportSessionToCsv(
        context: Context,
        session: DriveSession,
        measurements: List<LteMeasurementEntity>
    ): File {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply {
            if (!exists()) mkdirs()
        }

        val safeName = session.sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTimeMs))
        val file = File(exportDir, "cellscope_${safeName}_$timestamp.csv")

        FileWriter(file).use { writer ->
            writer.appendLine(header)
            for (m in measurements) {
                val row = listOf(
                    m.timestampIso,
                    m.latitude?.toString() ?: "",
                    m.longitude?.toString() ?: "",
                    m.speedKmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                    m.servingPci?.toString() ?: "",
                    m.rsrpDbm?.toString() ?: "",
                    m.rsrqDb?.toString() ?: "",
                    m.sinrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    m.handoverTrigger.toString(),
                    m.tac?.toString() ?: "",
                    m.earfcn?.toString() ?: "",
                    m.rssiDbm?.toString() ?: "",
                    m.networkType,
                    m.operatorAlpha ?: ""
                ).joinToString(",")
                writer.appendLine(row)
            }
        }

        return file
    }

    fun createShareIntent(context: Context, csvFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CellScope Drive Test Export: ${csvFile.name}")
            clipData = android.content.ClipData.newRawUri("CSV Telemetry", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
