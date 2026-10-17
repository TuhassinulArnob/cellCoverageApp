package com.cellscope.drive

import android.app.Application
import com.cellscope.drive.data.db.CellScopeDatabase
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class CellScopeApplication : Application() {

    val database: CellScopeDatabase by lazy {
        CellScopeDatabase.getDatabase(this)
    }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        try {
            HttpRequestUtil.setPrintRequestUrlOnFailure(true)
            HttpRequestUtil.setLogEnabled(true)
        } catch (e: Exception) {
            android.util.Log.w("CellScopeApplication", "HttpRequestUtil configuration warning: ${e.message}")
        }
    }
}
