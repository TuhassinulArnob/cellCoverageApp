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
import com.cellscope.drive.model.NetworkGeneration
import com.cellscope.drive.model.ObservedCell
import com.cellscope.drive.model.ServingStatus

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
    val lastScanTimeMs: Long,
    val supports5gNr: Boolean = false,
    val nrState: String = "LTE_ONLY",
    val nrPci: Int? = null,
    val nci: Long? = null,
    val nrArfcn: Int? = null,
    val nrAvailabilityState: String = "NOT_CONFIGURED",
    val secondaryCellsCount: Int = 0,
    val unconfirmedCellsCount: Int = 0,
    val voiceNetworkType: String = "UNKNOWN",
    val serviceState: String = "IN_SERVICE",
    val dataConnectionState: String = "CONNECTED",
    val callState: String = "IDLE",
    val normalizedGen: String = "UNKNOWN"
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
        lastScanTimeMs = 0L,
        supports5gNr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        nrState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "LTE_ONLY" else "UNSUPPORTED",
        nrPci = null,
        nci = null,
        nrArfcn = null,
        nrAvailabilityState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "NOT_CONFIGURED" else "NO_HARDWARE"
    )
    @Volatile
    private var hasSeenNonZeroRssnr: Boolean = false

    private fun getActiveTelephonyManager(): TelephonyManager? {
        if (telephonyManager == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val subId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                if (subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return telephonyManager.createForSubscriptionId(subId)
                }
            } catch (_: Exception) {
            }
        }
        return telephonyManager
    }

    fun getOperatorIdentity(): OperatorIdentity {
        val serving = latestMetrics
        val activeTm = getActiveTelephonyManager() ?: telephonyManager
        val now = System.currentTimeMillis()
        val isStale = (now - serving.timestampMs > 15000L) && serving.timestampMs > 0L
        val simSlotIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                val subId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                } else null
                if (subId != null && subId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex
                } else null
            } catch (_: Exception) { null }
        } else null

        return OperatorIdentity(
            simOperatorName = activeTm?.simOperatorName?.takeIf { it.isNotBlank() },
            simOperatorNumeric = activeTm?.simOperator?.takeIf { it.isNotBlank() },
            simCountryIso = activeTm?.simCountryIso?.takeIf { it.isNotBlank() },
            simSlotIndex = simSlotIndex,
            networkOperatorName = activeTm?.networkOperatorName?.takeIf { it.isNotBlank() },
            networkOperatorNumeric = activeTm?.networkOperator?.takeIf { it.isNotBlank() },
            networkCountryIso = activeTm?.networkCountryIso?.takeIf { it.isNotBlank() },
            servingOperatorAlpha = serving.operatorAlpha,
            servingOperatorNumeric = serving.operatorNumeric,
            dataNetworkType = getNetworkTypeString(),
            isRoaming = activeTm?.isNetworkRoaming ?: false,
            servingPci = serving.servingPci,
            tac = serving.tac,
            eci = serving.eci,
            earfcn = serving.earfcn,
            timestampMs = serving.timestampMs,
            serviceState = serving.serviceState ?: "IN_SERVICE",
            dataConnectionState = serving.dataState ?: "CONNECTED",
            dataSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() } catch (_: Exception) { null }
            } else null,
            isStale = isStale,
            activeRat = serving.servingRat ?: serving.networkGen?.code ?: "LTE"
        )
    }

    @SuppressLint("MissingPermission")
    fun sampleCurrentMetrics(): LteMetrics {
        val activeTm = getActiveTelephonyManager() ?: telephonyManager
        if (activeTm == null) {
            val none = LteMetrics(
                networkType = "NO_TELEPHONY",
                serviceState = "OUT_OF_SERVICE",
                networkGen = NetworkGeneration.OUT_OF_SERVICE
            )
            latestMetrics = none
            onMetricsUpdated(none)
            return none
        }

        val operatorName = activeTm.networkOperatorName.takeIf { it.isNotBlank() }
        val operatorNumeric = activeTm.networkOperator.takeIf { it.isNotBlank() }
        val netType = getNetworkTypeString()
        val now = System.currentTimeMillis()

        // Read distinct cellular data and voice bearer states
        val dataNetInt = try {
            activeTm.dataNetworkType
        } catch (_: Exception) {
            0
        }

        val voiceNetInt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                activeTm.voiceNetworkType
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }

        val rawServiceState = try {
            activeTm.serviceState?.state
        } catch (_: Exception) {
            null
        }
        val serviceStateStr = NetworkGeneration.getServiceStateName(rawServiceState)

        val rawDataState = try {
            activeTm.dataState
        } catch (_: Exception) {
            null
        }
        val dataStateStr = NetworkGeneration.getDataStateName(rawDataState)

        val rawCallState = try {
            activeTm.callState
        } catch (_: Exception) {
            null
        }
        val callStateStr = NetworkGeneration.getCallStateName(rawCallState)

        try {
            val cellInfoList: List<CellInfo>? = activeTm.allCellInfo

            if (cellInfoList.isNullOrEmpty()) {
                val detailedDataTech = NetworkGeneration.getDetailedTechName(dataNetInt)
                val detailedVoiceTech = NetworkGeneration.getDetailedTechName(voiceNetInt)
                val normalizedGen = if (serviceStateStr == "OUT_OF_SERVICE" || serviceStateStr == "POWER_OFF") {
                    NetworkGeneration.OUT_OF_SERVICE
                } else {
                    NetworkGeneration.fromNetworkType(dataNetInt)
                }

                val fallback = LteMetrics(
                    networkType = netType,
                    operatorAlpha = operatorName,
                    operatorNumeric = operatorNumeric,
                    timestampMs = now,
                    dataNetworkType = detailedDataTech,
                    voiceNetworkType = detailedVoiceTech,
                    networkGen = normalizedGen,
                    serviceState = serviceStateStr,
                    dataState = dataStateStr,
                    callState = callStateStr
                )
                latestMetrics = fallback
                updateDiagnostics(emptyList(), operatorName, netType, now, detailedVoiceTech, serviceStateStr, dataStateStr, callStateStr, normalizedGen.code)
                onMetricsUpdated(fallback)
                return fallback
            }

            val rawCandidates = mutableListOf<RawCellCandidate>()

            for (info in cellInfoList) {
                val candidate = parseCellCandidate(info, operatorName, operatorNumeric, now)
                if (candidate != null) {
                    rawCandidates.add(candidate)
                }
            }

            // Authoritatively classify serving status across all scanned cells
            val observedCells = ServingCellClassifier.classify(rawCandidates)

            // Primary serving cell is the single authoritative PCell
            val primaryServingCell = observedCells.firstOrNull { it.servingStatus == ServingStatus.PRIMARY_SERVING }
            val neighborList = observedCells.filter { it.servingStatus == ServingStatus.NEIGHBOR }

            // Determine 5G NR architecture and availability state
            val nrCells = observedCells.filter { it.networkType == "5G_NR" }
            val primaryIsNr = primaryServingCell?.networkType == "5G_NR"
            val hasNrCell = nrCells.isNotEmpty()
            val netIsNr = netType == "5G_NR"

            val nrState = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "UNSUPPORTED"
                primaryIsNr -> "SA"
                hasNrCell || netIsNr -> "NSA"
                else -> "LTE_ONLY"
            }

            val nrAvailabilityState = when (nrState) {
                "SA", "NSA" -> "CONNECTED"
                "LTE_ONLY" -> "NOT_CONFIGURED"
                else -> "NO_HARDWARE"
            }

            val bestNrCell = nrCells.maxByOrNull { it.rsrpDbm ?: -999 }
            val effectiveNci = if (primaryIsNr) primaryServingCell?.nci else bestNrCell?.nci
            val effectiveNrArfcn = if (primaryIsNr) primaryServingCell?.nrArfcn else bestNrCell?.nrArfcn
            val effectiveCsiRsrp = if (primaryIsNr) primaryServingCell?.csiRsrpDbm else bestNrCell?.csiRsrpDbm
            val effectiveCsiRsrq = if (primaryIsNr) primaryServingCell?.csiRsrqDb else bestNrCell?.csiRsrqDb
            val effectiveCsiSinr = if (primaryIsNr) primaryServingCell?.csiSinrDb else bestNrCell?.csiSinrDb

            val detailedDataTech = NetworkGeneration.getDetailedTechName(dataNetInt, nrState)
            val detailedVoiceTech = NetworkGeneration.getDetailedTechName(voiceNetInt)

            val normalizedGen = if (serviceStateStr == "OUT_OF_SERVICE" || serviceStateStr == "POWER_OFF") {
                NetworkGeneration.OUT_OF_SERVICE
            } else {
                val genFromCell = primaryServingCell?.networkType?.let { NetworkGeneration.fromString(it) }
                if (genFromCell != null && genFromCell != NetworkGeneration.UNKNOWN) {
                    if (genFromCell == NetworkGeneration.GEN_4G && nrState == "NSA") NetworkGeneration.GEN_5G else genFromCell
                } else {
                    NetworkGeneration.fromNetworkType(dataNetInt, nrState)
                }
            }

            val metrics = LteMetrics(
                networkType = primaryServingCell?.networkType ?: netType,
                operatorAlpha = primaryServingCell?.operatorAlpha ?: operatorName,
                operatorNumeric = primaryServingCell?.operatorNumeric ?: operatorNumeric,
                servingPci = primaryServingCell?.pci,
                tac = primaryServingCell?.tac,
                earfcn = primaryServingCell?.earfcn,
                bandwidthKhz = primaryServingCell?.bandwidthKhz,
                rsrpDbm = primaryServingCell?.rsrpDbm,
                rsrqDb = primaryServingCell?.rsrqDb,
                rssiDbm = primaryServingCell?.rssiDbm,
                sinrDb = primaryServingCell?.sinrDb,
                eci = primaryServingCell?.eci,
                cqi = primaryServingCell?.cqi,
                timingAdvance = primaryServingCell?.timingAdvance,
                isServing = primaryServingCell != null,
                neighborCells = neighborList,
                allCells = observedCells,
                nci = effectiveNci,
                nrArfcn = effectiveNrArfcn,
                csiRsrpDbm = effectiveCsiRsrp,
                csiRsrqDb = effectiveCsiRsrq,
                csiSinrDb = effectiveCsiSinr,
                nrState = nrState,
                nrAvailabilityState = nrAvailabilityState,
                timestampMs = now,
                dataNetworkType = detailedDataTech,
                voiceNetworkType = detailedVoiceTech,
                networkGen = normalizedGen,
                serviceState = serviceStateStr,
                dataState = dataStateStr,
                callState = callStateStr,
                servingRat = primaryServingCell?.servingRat ?: when (normalizedGen) {
                    NetworkGeneration.GEN_5G -> "5G"
                    NetworkGeneration.GEN_4G -> "4G"
                    NetworkGeneration.GEN_3G -> "3G"
                    NetworkGeneration.GEN_2G -> "2G"
                    else -> null
                },
                channelNumber = primaryServingCell?.channelNumber,
                channelType = primaryServingCell?.channelType,
                primarySignalDbm = primaryServingCell?.primarySignalDbm,
                signalMetricType = primaryServingCell?.signalMetricType,
                secondarySignalDb = primaryServingCell?.secondarySignalDb
            )

            latestMetrics = metrics
            updateDiagnostics(observedCells, operatorName, netType, now, detailedVoiceTech, serviceStateStr, dataStateStr, callStateStr, normalizedGen.code)
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
            timestampMs = now,
            serviceState = serviceStateStr,
            dataState = dataStateStr,
            callState = callStateStr
        )
        latestMetrics = empty
        onMetricsUpdated(empty)
        return empty
    }

    private fun resolveCandidateOperator(
        isRegistered: Boolean,
        cellPlmn: String?,
        cellAlpha: String?,
        simOperatorNumeric: String?,
        simOperatorName: String?
    ): Pair<String?, String?> {
        return if (isRegistered) {
            val plmn = cellPlmn ?: simOperatorNumeric
            val alpha = if (plmn != null) {
                BangladeshMccMnc.resolveOperatorName(plmn, cellAlpha ?: simOperatorName)
            } else {
                cellAlpha ?: simOperatorName
            }
            Pair(plmn, alpha)
        } else {
            // Neighbor cell: Strictly NEVER inherit active SIM operator identity!
            if (cellPlmn != null) {
                val alpha = BangladeshMccMnc.resolveOperatorName(cellPlmn, cellAlpha)
                Pair(cellPlmn, alpha)
            } else {
                Pair(null, cellAlpha ?: "Unknown (Unbroadcast PLMN)")
            }
        }
    }

    private fun parseCellCandidate(
        info: CellInfo,
        operatorName: String?,
        operatorNumeric: String?,
        timestampMs: Long
    ): RawCellCandidate? {
        val rawConnStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.cellConnectionStatus
        } else {
            RawCellCandidate.CONNECTION_UNKNOWN
        }

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

                // Extract cell-specific PLMN and operator identity
                val cellMcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mccString
                } else {
                    @Suppress("DEPRECATION") identity.mcc.takeIf { it != Integer.MAX_VALUE && it > 0 }?.toString()
                }
                val cellMnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mncString
                } else {
                    @Suppress("DEPRECATION") identity.mnc.takeIf { it != Integer.MAX_VALUE && it >= 0 }?.toString()
                }
                val cellPlmn = if (!cellMcc.isNullOrBlank() && !cellMnc.isNullOrBlank()) "$cellMcc$cellMnc" else null
                val cellAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?: identity.operatorAlphaShort?.toString()?.takeIf { it.isNotBlank() }
                } else null
                val (effectivePlmn, effectiveAlpha) = resolveCandidateOperator(
                    isRegistered = info.isRegistered,
                    cellPlmn = cellPlmn,
                    cellAlpha = cellAlpha,
                    simOperatorNumeric = operatorNumeric,
                    simOperatorName = operatorName
                )

                // RSSNR in LTE is given in tenths of a dB (-200..300 = -20.0 to 30.0 dB)
                val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val rawRssnr = signal.rssnr
                    if (rawRssnr in -200..300) {
                        if (rawRssnr != 0) {
                            hasSeenNonZeroRssnr = true
                            rawRssnr / 10.0
                        } else {
                            if (hasSeenNonZeroRssnr) 0.0 else null
                        }
                    } else null
                } else null

                val cqi = signal.cqi.takeIf { it in 1..15 }
                val ta = signal.timingAdvance.takeIf { it in 0..1282 }

                return RawCellCandidate(
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
                    isRegistered = info.isRegistered,
                    connectionStatus = rawConnStatus,
                    networkType = "LTE",
                    operatorAlpha = effectiveAlpha,
                    operatorNumeric = effectivePlmn,
                    timestampMs = timestampMs,
                    servingRat = "4G",
                    channelNumber = earfcn,
                    channelType = "EARFCN",
                    primarySignalDbm = rsrp,
                    signalMetricType = "LTE_RSRP",
                    secondarySignalDb = rsrq?.toDouble() ?: sinr
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr -> {
                val signal = info.cellSignalStrength as? CellSignalStrengthNr
                val identity = info.cellIdentity as? android.telephony.CellIdentityNr
                val pci = identity?.pci?.takeIf { it in 0..1007 }
                val tac = identity?.tac?.takeIf { it in 1..16777214 }
                val nci = identity?.nci?.takeIf { it in 1L..68719476735L }
                val nrArfcn = identity?.nrarfcn?.takeIf { it in 0..3279165 }
                val ssRsrp = signal?.ssRsrp?.takeIf { it in -156..-31 }
                val ssRsrq = signal?.ssRsrq?.takeIf { it in -43..20 }
                val ssSinr = signal?.ssSinr?.takeIf { it in -23..40 }?.toDouble()
                val csiRsrp = signal?.csiRsrp?.takeIf { it in -156..-31 }
                val csiRsrq = signal?.csiRsrq?.takeIf { it in -43..20 }
                val csiSinr = signal?.csiSinr?.takeIf { it in -23..40 }?.toDouble()

                val cellMcc = identity?.mccString
                val cellMnc = identity?.mncString
                val cellPlmn = if (!cellMcc.isNullOrBlank() && !cellMnc.isNullOrBlank()) "$cellMcc$cellMnc" else null
                val cellAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity?.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?: identity?.operatorAlphaShort?.toString()?.takeIf { it.isNotBlank() }
                } else null
                val (effectivePlmn, effectiveAlpha) = resolveCandidateOperator(
                    isRegistered = info.isRegistered,
                    cellPlmn = cellPlmn,
                    cellAlpha = cellAlpha,
                    simOperatorNumeric = operatorNumeric,
                    simOperatorName = operatorName
                )

                return RawCellCandidate(
                    pci = pci,
                    tac = tac,
                    nci = nci,
                    nrArfcn = nrArfcn,
                    rsrpDbm = ssRsrp ?: csiRsrp,
                    rsrqDb = ssRsrq ?: csiRsrq,
                    sinrDb = ssSinr ?: csiSinr,
                    csiRsrpDbm = csiRsrp,
                    csiRsrqDb = csiRsrq,
                    csiSinrDb = csiSinr,
                    isRegistered = info.isRegistered,
                    connectionStatus = rawConnStatus,
                    networkType = "5G_NR",
                    operatorAlpha = effectiveAlpha,
                    operatorNumeric = effectivePlmn,
                    timestampMs = timestampMs,
                    servingRat = "5G",
                    channelNumber = nrArfcn,
                    channelType = "NR_ARFCN",
                    primarySignalDbm = ssRsrp ?: csiRsrp,
                    signalMetricType = "NR_SS_RSRP",
                    secondarySignalDb = ssRsrq?.toDouble() ?: csiRsrq?.toDouble()
                )
            }

            info is CellInfoWcdma -> {
                val sig = info.cellSignalStrength
                val rscp = sig.dbm.takeIf { it in -120..-24 }
                val identity = info.cellIdentity
                val uarfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    identity.uarfcn.takeIf { it in 0..16383 }
                } else null
                val psc = identity.psc.takeIf { it in 0..511 }
                val cid = identity.cid.takeIf { it in 1..268435455 }
                val lac = identity.lac.takeIf { it in 1..65535 }

                val cellMcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mccString
                } else {
                    @Suppress("DEPRECATION") identity.mcc.takeIf { it != Integer.MAX_VALUE && it > 0 }?.toString()
                }
                val cellMnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mncString
                } else {
                    @Suppress("DEPRECATION") identity.mnc.takeIf { it != Integer.MAX_VALUE && it >= 0 }?.toString()
                }
                val cellPlmn = if (!cellMcc.isNullOrBlank() && !cellMnc.isNullOrBlank()) "$cellMcc$cellMnc" else null
                val cellAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?: identity.operatorAlphaShort?.toString()?.takeIf { it.isNotBlank() }
                } else null
                val (effectivePlmn, effectiveAlpha) = resolveCandidateOperator(
                    isRegistered = info.isRegistered,
                    cellPlmn = cellPlmn,
                    cellAlpha = cellAlpha,
                    simOperatorNumeric = operatorNumeric,
                    simOperatorName = operatorName
                )

                return RawCellCandidate(
                    pci = psc,
                    tac = lac,
                    eci = cid,
                    earfcn = null,
                    rsrpDbm = null, // Strictly null: 3G uses RSCP, not RSRP!
                    rssiDbm = rscp,
                    isRegistered = info.isRegistered,
                    connectionStatus = rawConnStatus,
                    networkType = "3G_WCDMA",
                    operatorAlpha = effectiveAlpha,
                    operatorNumeric = effectivePlmn,
                    timestampMs = timestampMs,
                    servingRat = "3G",
                    channelNumber = uarfcn,
                    channelType = "UARFCN",
                    primarySignalDbm = rscp,
                    signalMetricType = "WCDMA_RSCP",
                    secondarySignalDb = null
                )
            }

            info is CellInfoGsm -> {
                val sig = info.cellSignalStrength
                val dbm = sig.dbm.takeIf { it in -120..-40 }
                val identity = info.cellIdentity
                val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    identity.arfcn.takeIf { it in 0..1023 }
                } else null
                val bsic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    identity.bsic.takeIf { it in 0..63 }
                } else null
                val cid = identity.cid.takeIf { it in 1..65535 }
                val lac = identity.lac.takeIf { it in 1..65535 }

                val cellMcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mccString
                } else {
                    @Suppress("DEPRECATION") identity.mcc.takeIf { it != Integer.MAX_VALUE && it > 0 }?.toString()
                }
                val cellMnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identity.mncString
                } else {
                    @Suppress("DEPRECATION") identity.mnc.takeIf { it != Integer.MAX_VALUE && it >= 0 }?.toString()
                }
                val cellPlmn = if (!cellMcc.isNullOrBlank() && !cellMnc.isNullOrBlank()) "$cellMcc$cellMnc" else null
                val cellAlpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                        ?: identity.operatorAlphaShort?.toString()?.takeIf { it.isNotBlank() }
                } else null
                val (effectivePlmn, effectiveAlpha) = resolveCandidateOperator(
                    isRegistered = info.isRegistered,
                    cellPlmn = cellPlmn,
                    cellAlpha = cellAlpha,
                    simOperatorNumeric = operatorNumeric,
                    simOperatorName = operatorName
                )

                return RawCellCandidate(
                    pci = bsic,
                    tac = lac,
                    eci = cid,
                    earfcn = null,
                    rsrpDbm = null, // Strictly null: GSM uses RSSI, not RSRP!
                    rssiDbm = dbm,
                    isRegistered = info.isRegistered,
                    connectionStatus = rawConnStatus,
                    networkType = "2G_GSM",
                    operatorAlpha = effectiveAlpha,
                    operatorNumeric = effectivePlmn,
                    timestampMs = timestampMs,
                    servingRat = "2G",
                    channelNumber = arfcn,
                    channelType = "ARFCN",
                    primarySignalDbm = dbm,
                    signalMetricType = "GSM_RSSI",
                    secondarySignalDb = null
                )
            }

            else -> return null
        }
    }

    private fun updateDiagnostics(
        cells: List<ObservedCell>,
        operatorName: String?,
        netType: String,
        timestampMs: Long,
        voiceNetType: String = "UNKNOWN",
        serviceState: String = "IN_SERVICE",
        dataState: String = "CONNECTED",
        callState: String = "IDLE",
        normalizedGen: String = "UNKNOWN"
    ) {
        val servingCount = cells.count { it.servingStatus == ServingStatus.PRIMARY_SERVING }
        val secondaryCount = cells.count { it.servingStatus == ServingStatus.SECONDARY_SERVING }
        val unconfirmedCount = cells.count { it.servingStatus == ServingStatus.SERVING_UNCONFIRMED }
        val neighborCount = cells.count { it.servingStatus == ServingStatus.NEIGHBOR }
        val hasSinr = cells.any { it.sinrDb != null }
        val hasTa = cells.any { it.timingAdvance != null }
        val nrCells = cells.filter { it.networkType == "5G_NR" }
        val primaryIsNr = cells.any { it.servingStatus == ServingStatus.PRIMARY_SERVING && it.networkType == "5G_NR" }
        val hasNr = nrCells.isNotEmpty() || netType == "5G_NR"
        val nrState = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "UNSUPPORTED"
            primaryIsNr -> "SA"
            hasNr -> "NSA"
            else -> "LTE_ONLY"
        }
        val bestNr = nrCells.firstOrNull()

        latestDiagnostics = TelephonyDiagnostics(
            hasTelephonyHardware = true,
            networkOperator = operatorName,
            simOperator = telephonyManager?.simOperatorName,
            dataNetworkType = netType,
            totalCellsObserved = cells.size,
            servingCellsCount = servingCount,
            secondaryCellsCount = secondaryCount,
            unconfirmedCellsCount = unconfirmedCount,
            neighborCellsCount = neighborCount,
            supportsNeighborCells = neighborCount > 0,
            supportsSinr = hasSinr,
            supportsTimingAdvance = hasTa,
            lastScanTimeMs = timestampMs,
            supports5gNr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            nrState = nrState,
            nrPci = bestNr?.pci,
            nci = bestNr?.nci,
            nrArfcn = bestNr?.nrArfcn,
            nrAvailabilityState = if (nrState == "SA" || nrState == "NSA") "CONNECTED" else if (nrState == "LTE_ONLY") "NOT_CONFIGURED" else "NO_HARDWARE",
            voiceNetworkType = voiceNetType,
            serviceState = serviceState,
            dataConnectionState = dataState,
            callState = callState,
            normalizedGen = normalizedGen
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
