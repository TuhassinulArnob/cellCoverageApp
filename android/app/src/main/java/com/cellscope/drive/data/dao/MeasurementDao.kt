package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.LteMeasurementEntity

@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: LteMeasurementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurements: List<LteMeasurementEntity>)

    @Query("SELECT * FROM lte_measurements WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getMeasurementsForSession(sessionId: Long): List<LteMeasurementEntity>

    @Query("SELECT COUNT(*) FROM lte_measurements WHERE sessionId = :sessionId")
    suspend fun getCountForSession(sessionId: Long): Int

    @Query("SELECT * FROM lte_measurements WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestForSession(sessionId: Long): LteMeasurementEntity?

    @Query("SELECT DISTINCT servingPci FROM lte_measurements WHERE sessionId = :sessionId AND servingPci IS NOT NULL")
    suspend fun getDistinctPcisForSession(sessionId: Long): List<Int>

    @Query("SELECT AVG(rsrpDbm) FROM lte_measurements WHERE sessionId = :sessionId AND rsrpDbm IS NOT NULL")
    suspend fun getAvgRsrpForSession(sessionId: Long): Double?

    @Query("DELETE FROM lte_measurements WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
