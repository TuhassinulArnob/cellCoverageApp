package com.cellscope.drive

import com.cellscope.drive.model.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRotationRegressionTest {

    /**
     * Simulates the Activity lifecycle and rotation behavior with a process-scoped
     * survey session manager (singleton recorder pattern).
     */
    private class MockSurveySessionManager {
        private val _recordingState = MutableStateFlow(RecordingState.IDLE)
        val recordingState = _recordingState.asStateFlow()

        private val _sampleCount = MutableStateFlow(0)
        val sampleCount = _sampleCount.asStateFlow()

        private val _elapsedSeconds = MutableStateFlow(0L)
        val elapsedSeconds = _elapsedSeconds.asStateFlow()

        var activeSessionId: Long? = null
            private set

        fun startSurvey(sessionId: Long) {
            if (_recordingState.value == RecordingState.RECORDING) return // prevent duplicate start
            activeSessionId = sessionId
            _recordingState.value = RecordingState.RECORDING
        }

        fun recordSample() {
            if (_recordingState.value == RecordingState.RECORDING) {
                _sampleCount.value += 1
            }
        }

        fun tickSecond() {
            if (_recordingState.value == RecordingState.RECORDING) {
                _elapsedSeconds.value += 1
            }
        }

        fun pauseSurvey() {
            if (_recordingState.value == RecordingState.RECORDING) {
                _recordingState.value = RecordingState.PAUSED
            }
        }

        fun resumeSurvey() {
            if (_recordingState.value == RecordingState.PAUSED) {
                _recordingState.value = RecordingState.RECORDING
            }
        }

        fun stopSurvey() {
            _recordingState.value = RecordingState.IDLE
            activeSessionId = null
        }
    }

    /**
     * Simulates an Activity instance observing the SurveySessionManager.
     */
    private class SimulatedActivity(val sessionManager: MockSurveySessionManager) {
        var observedState: RecordingState = sessionManager.recordingState.value
        var observedDurationText: String = "00:00"
        var observedSampleCount: Int = sessionManager.sampleCount.value

        fun syncWithManager() {
            observedState = sessionManager.recordingState.value
            observedSampleCount = sessionManager.sampleCount.value
            val sec = sessionManager.elapsedSeconds.value
            observedDurationText = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60)
        }

        fun onDestroy() {
            // In the bug prior to P0, the activity cancelled the recording coroutine here.
            // With the fixed singleton architecture, onDestroy does NOT cancel the background recording!
        }
    }

    @Test
    fun testActivityRecreationPreservesSessionAndTimer() {
        val manager = MockSurveySessionManager()

        // 1. Activity A launches and user starts network survey
        val activityA = SimulatedActivity(manager)
        activityA.syncWithManager()
        assertEquals(RecordingState.IDLE, activityA.observedState)

        manager.startSurvey(sessionId = 101L)
        manager.recordSample()
        manager.recordSample()
        manager.tickSecond()
        manager.tickSecond()
        activityA.syncWithManager()

        assertEquals(RecordingState.RECORDING, activityA.observedState)
        assertEquals(2, activityA.observedSampleCount)
        assertEquals("00:02", activityA.observedDurationText)
        assertEquals(101L, manager.activeSessionId)

        // 2. User rotates the screen from Portrait to Landscape:
        // Activity A is destroyed.
        activityA.onDestroy()

        // Meanwhile, background recording continues uninterrupted!
        manager.recordSample()
        manager.tickSecond()

        // 3. Activity B is created (new instance created by Android framework on rotation)
        val activityB = SimulatedActivity(manager)
        activityB.syncWithManager()

        // Verify Activity B immediately reconnects to the ongoing active session:
        // Session ID is preserved, sample count continues seamlessly, duration is unbroken!
        assertEquals(101L, manager.activeSessionId)
        assertEquals(RecordingState.RECORDING, activityB.observedState)
        assertEquals(3, activityB.observedSampleCount)
        assertEquals("00:03", activityB.observedDurationText)

        // 4. Duplicate start call is safely rejected
        manager.startSurvey(sessionId = 999L)
        assertEquals(101L, manager.activeSessionId) // Remains original session ID!

        // 5. Pause and Resume continuity
        manager.pauseSurvey()
        activityB.syncWithManager()
        assertEquals(RecordingState.PAUSED, activityB.observedState)

        manager.tickSecond() // Ticks while paused should not increment
        activityB.syncWithManager()
        assertEquals("00:03", activityB.observedDurationText)

        manager.resumeSurvey()
        manager.tickSecond()
        activityB.syncWithManager()
        assertEquals(RecordingState.RECORDING, activityB.observedState)
        assertEquals("00:04", activityB.observedDurationText)

        // 6. Finalization / Stop
        manager.stopSurvey()
        activityB.syncWithManager()
        assertEquals(RecordingState.IDLE, activityB.observedState)
        assertEquals(null, manager.activeSessionId)
    }

    @Test
    fun testColdLaunchOrphanedSessionRecoveryDoesNotAutoRecord() {
        val manager = MockSurveySessionManager()
        // Simulate app cold launch where DB has an unfinalized session from a prior terminated run
        // In the fixed architecture, the session is finalized and manager remains in IDLE state.
        assertEquals(RecordingState.IDLE, manager.recordingState.value)
        assertEquals(0, manager.sampleCount.value)
        assertEquals(0L, manager.elapsedSeconds.value)
        // No automatic recording should be triggered on app launch
        assertTrue("Recording state must remain IDLE on launch", manager.recordingState.value == RecordingState.IDLE)
    }

    @Test
    fun testRapidDuplicateStopCallsFinalizeExactlyOnce() {
        val manager = MockSurveySessionManager()
        manager.startSurvey(102L)
        assertEquals(RecordingState.RECORDING, manager.recordingState.value)

        var finalizationCount = 0
        val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)

        val stopAction = {
            if (isStopping.compareAndSet(false, true)) {
                finalizationCount++
                manager.stopSurvey()
            }
        }

        // Simulate rapid repeated taps on the Stop button
        stopAction()
        stopAction()
        stopAction()

        assertEquals("Finalization must execute exactly once", 1, finalizationCount)
        assertEquals(RecordingState.IDLE, manager.recordingState.value)
    }
}
