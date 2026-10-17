package com.cellscope.drive.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import com.cellscope.drive.model.LteMetrics
import com.cellscope.drive.model.ObservedCell

data class TelephonyDiagnostics(
    val hasTelephonyHardware: Boolean,
    val networkOperator: String?,
    val simOperator: String?,
    val dataNetworkType: String,
    val totalCellsObserved: Int,
    val servingCellsCount: Int,
    val neighborCellsCount: Int,
    val supportsNeighborCells: Boolean,
    val supportsSinr: Boolean,
    val supportsTimingAdvance: Boolean,
    val lastScanTimeMs: Long
)

class TelephonyCollector(
    private val context: Context,
    private val onMetricsUpdated: (LteMetrics) -> Unit
) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Volatile
    var latestMetrics: LteMetrics = LteMetrics()
        private set

    @Volatile
    var latestDiagnostics: TelephonyDiagnostics = TelephonyDiagnostics(
        hasTelephonyHardware = telephonyManager != null,
        networkOperator = null,
        simOperator = null,
        dataNetworkType = "UNKNOWN",
        totalCellsObserved = 0,
        servingCellsCount = 0,
        neighborCellsCount = 0,
        supportsNeighborCells = false,
        supportsSinr = false,
        supportsTimingAdvance = false,
        lastScanTimeMs = 0L
    )
    @Volatile
    private var hasSeenNonZeroRssnr: Boolean = false

    fun getOperatorIdentity(): OperatorIdentity {
        val serving = latestMetrics
        return OperatorIdentity(
            simOperatorName = telephonyManager?.simOperatorName?.takeIf { it.isNotBlank() },
            simOperatorNumeric = telephonyManager?.simOperator?.takeIf { it.isNotBlank() },
            simCountryIso = telephonyManager?.simCountryIso?.takeIf { it.isNotBlank() },
            networkOperatorName = telephonyManager?.networkOperatorName?.takeIf { it.isNotBlank() },
            networkOperatorNumeric = telephonyManager?.networkOperator?.takeIf { it.isNotBlank() },
            networkCountryIso = telephonyManager?.networkCountryIso?.takeIf { it.isNotBlank() },
            dataNetworkType = getNetworkTypeString(),
            isRoaming = telephonyManager?.isNetworkRoaming ?: false,
            servingPci = serving.servingPci,
            tac = serving.tac,
            eci = serving.eci,
            earfcn = serving.earfcn,
            timestampMs = serving.timestampMs
        )
    }

    @SuppressLint("MissingPermission")
    fun sampleCurrentMetrics(): LteMetrics {
        if (telephonyManager == null) {
            val none = LteMetrics(networkType = "NO_TELEPHONY")
            latestMetrics = none
            onMetricsUpdated(none)
            return none
        }

        val operatorName = telephonyManager.networkOperatorName.takeIf { it.isNotBlank() }
        val operatorNumeric = telephonyManager.networkOperator.takeIf { it.isNotBlank() }
        val netType = getNetworkTypeString()
        val now = System.currentTimeMillis()

        try {
            val cellInfoList: List<CellInfo>? = telephonyManager.allCellInfo

            if (cellInfoList.isNullOrEmpty()) {
                val fallback = LteMetrics(
                    networkType = netType,
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = now
                )
                latestMetrics = fallback
                updateDiagnostics(emptyList(), operatorName, netType, now)
                onMetricsUpdated(fallback)
                return fallback
            }

            val observedCells = mutableListOf<ObservedCell>()
            var primaryServingCell: ObservedCell? = null

            for (info in cellInfoList) {
                val parsed = parseCellInfo(info, operatorName, operatorNumeric, now)
                if (parsed != null) {
                    observedCells.add(parsed)
                    if (parsed.isServing && primaryServingCell == null) {
                        primaryServingCell = parsed
                    }
                }
            }

            // If no registered cell was explicitly marked, pick first available with valid signal
            if (primaryServingCell == null && observedCells.isNotEmpty()) {
                primaryServingCell = observedCells.firstOrNull { it.rsrpDbm != null } ?: observedCells.first()
            }

            val neighborList = observedCells.filter { it != primaryServingCell }

            val serving = primaryServingCell ?: ObservedCell(
                pci = null,
                networkType = netType,
                operatorAlpha = operatorName,
                operatorNumeric = operatorNumeric,
                timestampMs = now
            )

            val metrics = LteMetrics(
                networkType = serving.networkType,
                operatorAlpha = serving.operatorAlpha ?: operatorName,
                operatorNumeric = serving.operatorNumeric ?: operatorNumeric,
                servingPci = serving.pci,
                tac = serving.tac,
                earfcn = serving.earfcn,
                bandwidthKhz = serving.bandwidthKhz,
                rsrpDbm = serving.rsrpDbm,
                rsrqDb = serving.rsrqDb,
                rssiDbm = serving.rssiDbm,
                sinrDb = serving.sinrDb,
                eci = serving.eci,
                cqi = serving.cqi,
                timingAdvance = serving.timingAdvance,
                isServing = serving.isServing,
                neighborCells = neighborList,
                allCells = observedCells,
                timestampMs = now
            )

            latestMetrics = metrics
            updateDiagnostics(observedCells, operatorName, netType, now)
            onMetricsUpdated(metrics)
            return metrics

        } catch (e: SecurityException) {
            Log.e("TelephonyCollector", "Missing phone/location permission: ${e.message}")
        } catch (e: Exception) {
            Log.e("TelephonyCollector", "Error collecting cell info: ${e.message}")
        }

        val empty = LteMetrics(
            networkType = netType,
            operatorAlpha = operatorName,
            operatorNumeric = operatorNumeric,
            timestampMs = now
        )
        latestMetrics = empty
        onMetricsUpdated(empty)
        return empty
    }

    private fun parseCellInfo(
        info: CellInfo,
        operatorName: String?,
        operatorNumeric: String?,
        timestampMs: Long
    ): ObservedCell? {
        when {
            info is CellInfoLte -> {
                val identity = info.cellIdentity
                val signal = info.cellSignalStrength

                val pci = identity.pci.takeIf { it in 0..503 }
                val tac = identity.tac.takeIf { it in 1..65534 }
                val eci = identity.ci.takeIf { it in 1..268435454 }

                val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.earfcn.takeIf { it in 0..262143 }
                } else null

                val bandwidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.bandwidth.takeIf { it > 0 }
                } else null

                val rsrp = signal.rsrp.takeIf { it in -140..-44 }
                val rsrq = signal.rsrq.takeIf { it in -30..-3 }
                val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    signal.rssi.takeIf { it in -120..-40 }
                } else null

                // RSSNR in LTE is given in tenths of a dB (-200..300 = -20.0 to 30.0 dB)
                val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val rawRssnr = signal.rssnr
                    if (rawRssnr in -200..300) {
                        if (rawRssnr != 0) {
                            hasSeenNonZeroRssnr = true
                            rawRssnr / 10.0
                        } else {
                            // If modem returns 0 constantly (e.g. MediaTek RIL default), only treat as 0.0 dB
                            // if device has actively demonstrated non-zero SINR reporting capability
                            if (hasSeenNonZeroRssnr) 0.0 else null
                        }
                    } else null
                } else null

                val cqi = signal.cqi.takeIf { it in 1..15 }
                val ta = signal.timingAdvance.takeIf { it in 0..1282 }

                return ObservedCell(
                    pci = pci,
                    tac = tac,
                    eci = eci,
                    earfcn = earfcn,
                    bandwidthKhz = bandwidth,
                    rsrpDbm = rsrp,
                    rsrqDb = rsrq,
                    rssiDbm = rssi,
                    sinrDb = sinr,
                    cqi = cqi,
                    timingAdvance = ta,
                    isServing = info.isRegistered,
                    networkType = "LTE",
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = timestampMs
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr -> {
                val signal = info.cellSignalStrength as? CellSignalStrengthNr
                val identity = info.cellIdentity as? android.telephony.CellIdentityNr
                val pci = identity?.pci?.takeIf { it in 0..1007 }
                val tac = identity?.tac?.takeIf { it in 1..16777214 }
                val rsrp = signal?.ssRsrp?.takeIf { it in -156..-31 }
                val rsrq = signal?.ssRsrq?.takeIf { it in -43..20 }
                val sinr = signal?.ssSinr?.takeIf { it in -23..40 }?.toDouble()

                return ObservedCell(
                    pci = pci,
                    tac = tac,
                    rsrpDbm = rsrp,
                    rsrqDb = rsrq,
                    sinrDb = sinr,
                    isServing = info.isRegistered,
                    networkType = "5G_NR",
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = timestampMs
                )
            }

            info is CellInfoWcdma -> {
                val sig = info.cellSignalStrength
                val rscp = sig.dbm.takeIf { it in -120..-24 }
                return ObservedCell(
                    pci = info.cellIdentity.psc.takeIf { it in 0..511 },
                    rsrpDbm = rscp,
                    isServing = info.isRegistered,
                    networkType = "3G_WCDMA",
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = timestampMs
                )
            }

            info is CellInfoGsm -> {
                val sig = info.cellSignalStrength
                val dbm = sig.dbm.takeIf { it in -113..-51 }
                return ObservedCell(
                    pci = null,
                    rsrpDbm = dbm,
                    isServing = info.isRegistered,
                    networkType = "2G_GSM",
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = timestampMs
                )
            }

            else -> return null
        }
    }

    private fun updateDiagnostics(
        cells: List<ObservedCell>,
        operatorName: String?,
        netType: String,
        timestampMs: Long
    ) {
        val servingCount = cells.count { it.isServing }
        val neighborCount = cells.count { !it.isServing }
        val hasSinr = cells.any { it.sinrDb != null }
        val hasTa = cells.any { it.timingAdvance != null }

        latestDiagnostics = TelephonyDiagnostics(
            hasTelephonyHardware = true,
            networkOperator = operatorName,
            simOperator = telephonyManager?.simOperatorName,
            dataNetworkType = netType,
            totalCellsObserved = cells.size,
            servingCellsCount = servingCount,
            neighborCellsCount = neighborCount,
            supportsNeighborCells = neighborCount > 0,
            supportsSinr = hasSinr,
            supportsTimingAdvance = hasTa,
            lastScanTimeMs = timestampMs
        )
    }

    @SuppressLint("MissingPermission")
    private fun getNetworkTypeString(): String {
        return try {
            when (telephonyManager?.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G_NR"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G_WCDMA"
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G_GSM"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
