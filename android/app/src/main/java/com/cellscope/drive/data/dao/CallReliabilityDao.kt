package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.CallReliabilityEventEntity

@Dao
interface CallReliabilityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallEvent(event: CallReliabilityEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallEvents(events: List<CallReliabilityEventEntity>)

    @Query("SELECT * FROM call_reliability_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getCallEventsForSession(sessionId: Long): List<CallReliabilityEventEntity>

    @Query("SELECT * FROM call_reliability_events WHERE sessionId = :sessionId AND classification = 'SUSPECTED_CALL_DROP' ORDER BY timestampMs ASC")
    suspend fun getSuspectedDropsForSession(sessionId: Long): List<CallReliabilityEventEntity>

    @Query("DELETE FROM call_reliability_events WHERE sessionId = :sessionId")
    suspend fun deleteCallEventsForSession(sessionId: Long)
}
