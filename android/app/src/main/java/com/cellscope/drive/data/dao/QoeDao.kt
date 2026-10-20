package com.cellscope.drive.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cellscope.drive.data.entity.ActiveQoeTestEntity

@Dao
interface QoeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQoeTest(test: ActiveQoeTestEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQoeTests(tests: List<ActiveQoeTestEntity>)

    @Query("SELECT * FROM active_qoe_tests WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun getTestsForSession(sessionId: Long): List<ActiveQoeTestEntity>

    @Query("SELECT * FROM active_qoe_tests WHERE sessionId = :sessionId AND success = 1 ORDER BY timestampMs ASC")
    suspend fun getSuccessfulTestsForSession(sessionId: Long): List<ActiveQoeTestEntity>

    @Query("SELECT COUNT(*) FROM active_qoe_tests WHERE sessionId = :sessionId")
    suspend fun getCountForSession(sessionId: Long): Int

    @Query("DELETE FROM active_qoe_tests WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
