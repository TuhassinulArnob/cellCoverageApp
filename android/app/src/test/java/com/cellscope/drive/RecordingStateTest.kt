package com.cellscope.drive

import com.cellscope.drive.model.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStateTest {

    @Test
    fun testStateTransitions() {
        var state = RecordingState.IDLE
        assertEquals(RecordingState.IDLE, state)

        // Start
        state = RecordingState.RECORDING
        assertEquals(RecordingState.RECORDING, state)

        // Pause
        state = RecordingState.PAUSED
        assertEquals(RecordingState.PAUSED, state)

        // Resume
        state = RecordingState.RECORDING
        assertEquals(RecordingState.RECORDING, state)

        // Stop
        state = RecordingState.IDLE
        assertEquals(RecordingState.IDLE, state)
    }
}
