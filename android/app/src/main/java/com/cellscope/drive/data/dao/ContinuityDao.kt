package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.NetworkContinuityEventEntity
import com.cellscope.drive.data.entity.ServiceHoleEntity

@Dao
interface ContinuityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContinuityEvent(event: NetworkContinuityEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContinuityEvents(events: List<NetworkContinuityEventEntity>)

    @Query("SELECT * FROM network_continuity_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getContinuityEventsForSession(sessionId: Long): List<NetworkContinuityEventEntity>

    @Query("SELECT * FROM network_continuity_events WHERE sessionId = :sessionId AND transitionCategory = 'DOWNGRADE' ORDER BY timestampMs ASC")
    suspend fun getDowngradesForSession(sessionId: Long): List<NetworkContinuityEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServiceHole(hole: ServiceHoleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServiceHoles(holes: List<ServiceHoleEntity>)

    @Query("SELECT * FROM service_holes WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getServiceHolesForSession(sessionId: Long): List<ServiceHoleEntity>

    @Query("DELETE FROM network_continuity_events WHERE sessionId = :sessionId")
    suspend fun deleteContinuityEventsForSession(sessionId: Long)

    @Query("DELETE FROM service_holes WHERE sessionId = :sessionId")
    suspend fun deleteServiceHolesForSession(sessionId: Long)
}
