package com.jarvis.audio.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSessionTest {
    @Test
    fun holdReleaseWithEmptyTranscriptResumesCapture() {
        val session = WakeSession()
        session.holdStart()
        assertEquals(WakeSession.Phase.HOLDING, session.phase)
        assertFalse(session.shouldCapture)
        session.holdRelease()
        assertEquals(WakeSession.Phase.LISTENING, session.phase)
        assertFalse(session.shouldCapture)
        session.emptyTranscript()
        assertEquals(WakeSession.Phase.PASSIVE, session.phase)
        assertTrue(session.shouldCapture)
    }

    @Test
    fun holdCancelErrorAndBusyAllResumeCapture() {
        val session = WakeSession()
        session.holdStart()
        session.cancel()
        assertTrue(session.shouldCapture)

        session.holdStart()
        session.holdRelease()
        session.error()
        assertTrue(session.shouldCapture)

        session.holdStart()
        session.holdRelease()
        session.recognizerBusy()
        assertTrue(session.shouldCapture)
    }

    @Test
    fun holdWithACommandStaysBusyUntilTtsAndFollowUpEnd() {
        val session = WakeSession()
        session.holdStart()
        session.holdRelease()
        session.commandHeard()
        assertEquals(WakeSession.Phase.THINKING, session.phase)
        assertFalse(session.shouldCapture)
        session.speak()
        session.ttsDone(followUp = true)
        assertEquals(WakeSession.Phase.LISTENING, session.phase)
        session.followUpEnded()
        assertTrue(session.shouldCapture)
    }

    @Test
    fun bargeAndAppVisibleReturnToPassive() {
        val session = WakeSession()
        session.speak()
        session.bargeIn()
        assertFalse(session.shouldCapture)
        session.bargeStop()
        assertTrue(session.shouldCapture)
        session.appVisible()
        assertTrue(session.shouldCapture)
        session.listen()
        session.appVisible()
        assertFalse(session.shouldCapture)
    }

    @Test
    fun watchdogRestartsOnlyWhenPassiveAndFramesHaveStopped() {
        val session = WakeSession()
        assertTrue(session.watchdogShouldRestart(nowMs = 4_000, lastFrameMs = 500))
        assertFalse(session.watchdogShouldRestart(nowMs = 2_500, lastFrameMs = 0))
        session.holdStart()
        assertFalse(session.watchdogShouldRestart(nowMs = 10_000, lastFrameMs = 0))
        session.emptyTranscript()
        session.listen()
        assertFalse(session.watchdogShouldRestart(nowMs = 10_000, lastFrameMs = 0))
        session.commandHeard()
        assertFalse(session.watchdogShouldRestart(nowMs = 10_000, lastFrameMs = 0))
        session.speak()
        assertFalse(session.watchdogShouldRestart(nowMs = 10_000, lastFrameMs = 0))
        session.ttsDone(followUp = false)
        assertTrue(session.watchdogShouldRestart(nowMs = 5_000, lastFrameMs = 1_000))
    }
}
