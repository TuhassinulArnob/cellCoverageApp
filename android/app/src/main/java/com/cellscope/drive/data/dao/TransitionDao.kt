package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.CellTransitionEntity

@Dao
interface TransitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransition(transition: CellTransitionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transitions: List<CellTransitionEntity>)

    @Query("SELECT * FROM cell_transitions WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getTransitionsForSession(sessionId: Long): List<CellTransitionEntity>

    @Query("SELECT COUNT(*) FROM cell_transitions WHERE sessionId = :sessionId")
    suspend fun getCountForSession(sessionId: Long): Int

    @Query("DELETE FROM cell_transitions WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
