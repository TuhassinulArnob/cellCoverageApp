package com.cellscope.drive.ui.map

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.nio.charset.StandardCharsets

class OfflineMapManager(private val context: Context) {

    private val offlineManager: OfflineManager = OfflineManager.getInstance(context)

    data class OfflineRegionInfo(
        val id: Long,
        val name: String,
        val completedResourceCount: Long,
        val completedResourceSize: Long,
        val isComplete: Boolean
    )

    interface DownloadCallback {
        fun onProgress(percentage: Int, completedResources: Long, totalResources: Long)
        fun onSuccess(regionInfo: OfflineRegionInfo)
        fun onError(error: String)
    }

    fun listRegions(onResult: (List<OfflineRegionInfo>) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = mutableListOf<OfflineRegionInfo>()
                if (offlineRegions != null && offlineRegions.isNotEmpty()) {
                    var pendingQueries = offlineRegions.size
                    for (region in offlineRegions) {
                        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                val name = parseRegionName(region.metadata)
                                val count = status?.completedResourceCount ?: 0L
                                val size = status?.completedResourceSize ?: 0L
                                val isComplete = status?.isComplete ?: false
                                list.add(OfflineRegionInfo(region.id, name, count, size, isComplete))

                                pendingQueries--
                                if (pendingQueries == 0) {
                                    onResult(list)
                                }
                            }

                            override fun onError(error: String?) {
                                pendingQueries--
                                if (pendingQueries == 0) {
                                    onResult(list)
                                }
                            }
                        })
                    }
                } else {
                    onResult(emptyList())
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Failed to list offline regions: $error")
                onResult(emptyList())
            }
        })
    }

    fun downloadArea(
        regionName: String,
        bounds: LatLngBounds,
        minZoom: Double = 10.0,
        maxZoom: Double = 14.0,
        callback: DownloadCallback
    ) {
        val pixelRatio = context.resources.displayMetrics.density
        // Use carto dark raster definition
        val definition = OfflineTilePyramidRegionDefinition(
            "asset://carto_dark.json",
            bounds,
            minZoom,
            maxZoom,
            pixelRatio
        )

        val metadata = createMetadata(regionName)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val percentage = if (status.requiredResourceCount >= 0) {
                                (100.0 * status.completedResourceCount / status.requiredResourceCount.coerceAtLeast(1L)).toInt()
                            } else {
                                0
                            }

                            callback.onProgress(percentage, status.completedResourceCount, status.requiredResourceCount)

                            if (status.isComplete) {
                                val info = OfflineRegionInfo(
                                    id = offlineRegion.id,
                                    name = regionName,
                                    completedResourceCount = status.completedResourceCount,
                                    completedResourceSize = status.completedResourceSize,
                                    isComplete = true
                                )
                                callback.onSuccess(info)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            Log.e(TAG, "Offline download error: ${error.reason}, ${error.message}")
                            callback.onError("${error.reason}: ${error.message}")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            Log.w(TAG, "Map tile count limit exceeded: $limit")
                            callback.onError("Tile count limit exceeded ($limit tiles)")
                        }
                    })

                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Create offline region error: $error")
                    callback.onError(error)
                }
            }
        )
    }

    fun deleteRegion(regionId: Long, onComplete: (Boolean) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val target = offlineRegions?.firstOrNull { it.id == regionId }
                if (target != null) {
                    target.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {
                            onComplete(true)
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "Failed to delete offline region: $error")
                            onComplete(false)
                        }
                    })
                } else {
                    onComplete(false)
                }
            }

            override fun onError(error: String) {
                onComplete(false)
            }
        })
    }

    private fun createMetadata(name: String): ByteArray {
        val json = JSONObject()
        json.put(FIELD_REGION_NAME, name)
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseRegionName(metadata: ByteArray?): String {
        if (metadata == null || metadata.isEmpty()) return "Unnamed Region"
        return try {
            val json = JSONObject(String(metadata, StandardCharsets.UTF_8))
            json.optString(FIELD_REGION_NAME, "Unnamed Region")
        } catch (e: Exception) {
            "Unnamed Region"
        }
    }

    companion object {
        private const val TAG = "OfflineMapManager"
        private const val FIELD_REGION_NAME = "region_name"
    }
}
