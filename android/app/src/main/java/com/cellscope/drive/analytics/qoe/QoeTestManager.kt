package com.cellscope.drive.analytics.qoe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class QoeTestExecutionResult(
    val testType: String, // PING, DOWNLOAD, UPLOAD, FULL_CYCLE
    val latencyMs: Long? = null,
    val downloadSpeedMbps: Double? = null,
    val uploadSpeedMbps: Double? = null,
    val bytesTransferred: Long = 0L,
    val durationMs: Long = 0L,
    val success: Boolean,
    val httpStatusCode: Int? = null,
    val errorMessage: String? = null
)

object QoeTestManager {

    const val DEFAULT_PING_URL = "https://1.1.1.1"
    const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=524288" // 512 KB
    const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val DEFAULT_TIMEOUT_MS = 6000

    /**
     * Opt-in active HTTP Ping / Latency test.
     */
    suspend fun runPingTest(
        endpointUrl: String = DEFAULT_PING_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): QoeTestExecutionResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
            }

            val statusCode = connection.responseCode
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

            if (statusCode in 200..399) {
                QoeTestExecutionResult(
                    testType = "PING",
                    latencyMs = elapsedMs,
                    durationMs = elapsedMs,
                    success = true,
                    httpStatusCode = statusCode
                )
            } else {
                QoeTestExecutionResult(
                    testType = "PING",
                    latencyMs = elapsedMs,
                    durationMs = elapsedMs,
                    success = false,
                    httpStatusCode = statusCode,
                    errorMessage = "HTTP error code: $statusCode"
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "PING",
                durationMs = elapsedMs,
                success = false,
                errorMessage = "Request timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "PING",
                durationMs = elapsedMs,
                success = false,
                errorMessage = e.message ?: "Unknown ping failure"
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Opt-in active HTTP streaming Download Throughput test.
     */
    suspend fun runDownloadTest(
        downloadUrl: String = DEFAULT_DOWNLOAD_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): QoeTestExecutionResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var totalBytesRead = 0L

        try {
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..399) {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                return@withContext QoeTestExecutionResult(
                    testType = "DOWNLOAD",
                    durationMs = elapsedMs,
                    success = false,
                    httpStatusCode = statusCode,
                    errorMessage = "Download HTTP error: $statusCode"
                )
            }

            inputStream = connection.inputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytesRead += bytesRead
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val elapsedSec = elapsedMs / 1000.0

            if (totalBytesRead > 0 && elapsedSec > 0.0) {
                val speedMbps = ((totalBytesRead * 8.0) / (elapsedSec * 1_000_000.0))
                val roundedMbps = Math.round(speedMbps * 100.0) / 100.0

                QoeTestExecutionResult(
                    testType = "DOWNLOAD",
                    downloadSpeedMbps = roundedMbps,
                    bytesTransferred = totalBytesRead,
                    durationMs = elapsedMs,
                    success = true,
                    httpStatusCode = statusCode
                )
            } else {
                QoeTestExecutionResult(
                    testType = "DOWNLOAD",
                    bytesTransferred = totalBytesRead,
                    durationMs = elapsedMs,
                    success = false,
                    httpStatusCode = statusCode,
                    errorMessage = "Zero bytes received during download test"
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "DOWNLOAD",
                bytesTransferred = totalBytesRead,
                durationMs = elapsedMs,
                success = false,
                errorMessage = "Download test timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "DOWNLOAD",
                bytesTransferred = totalBytesRead,
                durationMs = elapsedMs,
                success = false,
                errorMessage = e.message ?: "Download test failed"
            )
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * Opt-in active HTTP streaming Upload Throughput test.
     */
    suspend fun runUploadTest(
        uploadUrl: String = DEFAULT_UPLOAD_URL,
        payloadSizeBytes: Int = 131072, // 128 KB
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): QoeTestExecutionResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        var connection: HttpURLConnection? = null
        var outputStream: OutputStream? = null

        try {
            val dummyData = ByteArray(payloadSizeBytes) { 0x55.toByte() }
            val url = URL(uploadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setFixedLengthStreamingMode(payloadSizeBytes)
                setRequestProperty("Content-Type", "application/octet-stream")
            }

            outputStream = connection.outputStream
            outputStream.write(dummyData)
            outputStream.flush()

            val statusCode = connection.responseCode
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val elapsedSec = elapsedMs / 1000.0

            if (statusCode in 200..399 && elapsedSec > 0.0) {
                val speedMbps = ((payloadSizeBytes * 8.0) / (elapsedSec * 1_000_000.0))
                val roundedMbps = Math.round(speedMbps * 100.0) / 100.0

                QoeTestExecutionResult(
                    testType = "UPLOAD",
                    uploadSpeedMbps = roundedMbps,
                    bytesTransferred = payloadSizeBytes.toLong(),
                    durationMs = elapsedMs,
                    success = true,
                    httpStatusCode = statusCode
                )
            } else {
                QoeTestExecutionResult(
                    testType = "UPLOAD",
                    bytesTransferred = payloadSizeBytes.toLong(),
                    durationMs = elapsedMs,
                    success = false,
                    httpStatusCode = statusCode,
                    errorMessage = "Upload response code: $statusCode"
                )
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "UPLOAD",
                bytesTransferred = 0L,
                durationMs = elapsedMs,
                success = false,
                errorMessage = "Upload test timed out after ${timeoutMs}ms"
            )
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            QoeTestExecutionResult(
                testType = "UPLOAD",
                bytesTransferred = 0L,
                durationMs = elapsedMs,
                success = false,
                errorMessage = e.message ?: "Upload test failed"
            )
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }

    /**
     * Executes a complete active QoE test cycle: Ping -> Download -> Upload.
     */
    suspend fun runFullCycle(
        pingUrl: String = DEFAULT_PING_URL,
        downloadUrl: String = DEFAULT_DOWNLOAD_URL,
        uploadUrl: String = DEFAULT_UPLOAD_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): QoeTestExecutionResult = withContext(Dispatchers.IO) {
        val pingRes = runPingTest(pingUrl, timeoutMs)
        val dlRes = runDownloadTest(downloadUrl, timeoutMs)
        val ulRes = runUploadTest(uploadUrl, timeoutMs = timeoutMs)

        val anySuccess = pingRes.success || dlRes.success || ulRes.success
        val totalBytes = dlRes.bytesTransferred + ulRes.bytesTransferred
        val totalDuration = pingRes.durationMs + dlRes.durationMs + ulRes.durationMs

        val errList = listOfNotNull(pingRes.errorMessage, dlRes.errorMessage, ulRes.errorMessage)
        val combinedError = if (errList.isNotEmpty()) errList.joinToString(" | ") else null

        QoeTestExecutionResult(
            testType = "FULL_CYCLE",
            latencyMs = pingRes.latencyMs,
            downloadSpeedMbps = dlRes.downloadSpeedMbps,
            uploadSpeedMbps = ulRes.uploadSpeedMbps,
            bytesTransferred = totalBytes,
            durationMs = totalDuration,
            success = anySuccess,
            errorMessage = combinedError
        )
    }
}
