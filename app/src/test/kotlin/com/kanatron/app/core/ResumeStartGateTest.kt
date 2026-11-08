package com.kanatron.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeStartGateTest {
    @Test
    fun requestStartsImmediatelyOnlyWhenResumed() {
        val gate = ResumeStartGate()
        assertFalse(gate.onResume(true))
        assertTrue(gate.request(true))
    }

    @Test
    fun deferredRequestStartsOnceOnGrant() {
        val gate = ResumeStartGate()
        assertFalse(gate.request(false))
        assertTrue(gate.onResume(true))
        assertFalse(gate.onResume(true))
    }

    @Test
    fun deniedRequestIsSpent() {
        val gate = ResumeStartGate()
        assertFalse(gate.request(false))
        assertFalse(gate.onResume(false))
        assertFalse(gate.onResume(true))
    }
}
