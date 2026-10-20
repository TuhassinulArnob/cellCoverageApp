package com.cellscope.drive.analytics

import android.content.Context
import android.content.SharedPreferences
import com.cellscope.drive.util.TelecomTimeUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class SavedEventPlan(
    val id: String = UUID.randomUUID().toString(),
    val venue: EventVenue,
    val spec: CapacityScenarioSpec,
    val result: CapacityScenarioResult,
    val sourceSampleId: Long? = null,
    val isSurveyedPoint: Boolean = sourceSampleId != null,
    val isDraft: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Repository for managing saved and draft Event Capacity Plans (P5).
 * Guarantees strict data isolation: Deleting or editing a plan NEVER deletes or alters raw survey measurements.
 */
class EventPlanRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("cellscope_event_plans", Context.MODE_PRIVATE)
    private val plansMap = mutableMapOf<String, SavedEventPlan>()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun getAllPlans(): List<SavedEventPlan> {
        return plansMap.values.sortedByDescending { it.updatedAtMs }
    }

    @Synchronized
    fun getPlan(id: String): SavedEventPlan? = plansMap[id]

    @Synchronized
    fun savePlan(plan: SavedEventPlan): SavedEventPlan {
        val updated = plan.copy(updatedAtMs = System.currentTimeMillis())
        plansMap[updated.id] = updated
        persistToPrefs()
        return updated
    }

    /**
     * Strictly deletes the specified event plan.
     * Guaranteed: NEVER deletes or modifies underlying survey measurements.
     */
    @Synchronized
    fun deletePlan(id: String): Boolean {
        val removed = plansMap.remove(id) != null
        if (removed) {
            persistToPrefs()
        }
        return removed
    }

    @Synchronized
    fun clearAllPlans() {
        plansMap.clear()
        persistToPrefs()
    }

    fun exportPlanCsv(plan: SavedEventPlan): String {
        val sb = StringBuilder()
        sb.append("Plan_ID,Venue_Name,Center_Lat,Center_Lon,Coverage_Status,Sample_Count,Is_Surveyed,Attendees_Assumed,Active_Ratio_Assumed,Offered_Demand_DL_Mbps,Total_Capacity_Mbps,PRB_Load_Status,Created_At_BST\n")
        sb.append(formatPlanCsvRow(plan)).append("\n")
        return sb.toString()
    }

    fun exportCollectionCsv(): String {
        val sb = StringBuilder()
        sb.append("Plan_ID,Venue_Name,Center_Lat,Center_Lon,Coverage_Status,Sample_Count,Is_Surveyed,Attendees_Assumed,Active_Ratio_Assumed,Offered_Demand_DL_Mbps,Total_Capacity_Mbps,PRB_Load_Status,Created_At_BST\n")
        for (plan in getAllPlans()) {
            sb.append(formatPlanCsvRow(plan)).append("\n")
        }
        return sb.toString()
    }

    private fun formatPlanCsvRow(plan: SavedEventPlan): String {
        val v = plan.venue
        val r = plan.result
        val prbStatus = if (r.actualPrbUtilizationPct != null) "${r.actualPrbUtilizationPct}%" else "FURTHER_WORK_REQUIRED"
        val capStr = r.totalAvailableCapacityMbps?.toInt()?.toString() ?: "OPERATOR_COUNTERS_REQUIRED"
        val bdtTime = TelecomTimeUtils.formatBdtPretty(plan.createdAtMs)
        return "${plan.id},\"${v.name}\",${v.centerLat},${v.centerLon},${r.coverageEvidence.status.name},${r.coverageEvidence.sampleCountInArea},${plan.isSurveyedPoint},${plan.spec.additionalAttendees},${plan.spec.activeUserRatio},${r.offeredTrafficDlMbps.toInt()},$capStr,\"$prbStatus\",\"$bdtTime\""
    }

    private fun persistToPrefs() {
        try {
            val array = JSONArray()
            for (p in plansMap.values) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("venueName", p.venue.name)
                    put("venueCenterLat", p.venue.centerLat)
                    put("venueCenterLon", p.venue.centerLon)
                    put("venueRadiusM", p.venue.radiusMeters)
                    put("venuePreset", p.venue.eventType.name)
                    put("scenarioId", p.spec.scenarioId)
                    put("scenarioTitle", p.spec.title)
                    put("attendees", p.spec.additionalAttendees)
                    put("activeRatio", p.spec.activeUserRatio.toDouble())
                    put("trafficProfile", p.spec.trafficProfile.name)
                    put("hasCow", p.spec.hasTemporaryCow)
                    put("hasWifi", p.spec.hasWifiOffload)
                    put("hasTilt", p.spec.hasAntennaDowntiltOptimization)
                    put("sourceSampleId", p.sourceSampleId ?: -1L)
                    put("isDraft", p.isDraft)
                    put("createdAtMs", p.createdAtMs)
                    put("updatedAtMs", p.updatedAtMs)
                }
                array.put(obj)
            }
            prefs.edit().putString("saved_plans_json", array.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadFromPrefs() {
        val raw = prefs.getString("saved_plans_json", null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val preset = try { EventPreset.valueOf(obj.getString("venuePreset")) } catch (_: Exception) { EventPreset.CUSTOM }
                val profile = try { EventTrafficProfile.valueOf(obj.getString("trafficProfile")) } catch (_: Exception) { EventTrafficProfile.MODERATE }
                val venue = EventVenue.fromCoordinates(
                    lat = obj.getDouble("venueCenterLat"),
                    lon = obj.getDouble("venueCenterLon"),
                    name = obj.getString("venueName"),
                    eventName = obj.optString("eventName", obj.getString("venueName")),
                    radiusMeters = obj.getDouble("venueRadiusM"),
                    eventType = preset
                )
                val spec = CapacityScenarioSpec(
                    scenarioId = obj.getString("scenarioId"),
                    title = obj.getString("scenarioTitle"),
                    additionalAttendees = obj.getInt("attendees"),
                    activeUserRatio = obj.getDouble("activeRatio"),
                    trafficProfile = profile,
                    hasTemporaryCow = obj.getBoolean("hasCow"),
                    hasWifiOffload = obj.getBoolean("hasWifi"),
                    hasAntennaDowntiltOptimization = obj.getBoolean("hasTilt")
                )
                // Reconstruct result simulation
                val result = EventCapacitySimulator.simulateScenario(venue, spec, emptyList())
                val rawSampleId = obj.getLong("sourceSampleId")
                val sampleId = if (rawSampleId > 0) rawSampleId else null

                val plan = SavedEventPlan(
                    id = obj.getString("id"),
                    venue = venue,
                    spec = spec,
                    result = result,
                    sourceSampleId = sampleId,
                    isSurveyedPoint = sampleId != null,
                    isDraft = obj.optBoolean("isDraft", false),
                    createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                    updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                )
                plansMap[plan.id] = plan
            }
        } catch (_: Exception) {
        }
    }
}
