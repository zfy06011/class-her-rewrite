package com.classher.timetable

import com.classher.timetable.domain.CheckPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CheckPolicyTest {
    private val start = Instant.parse("2026-10-02T00:00:00Z")

    @Test fun strictlyMoreThanSixHoursAndGates() {
        val policy = CheckPolicy()
        policy.succeeded(start)
        assertFalse(policy.shouldCheck(start.plusSeconds(21600), true, false, false))
        assertTrue(policy.shouldCheck(start.plusSeconds(21601), true, false, false))
        assertFalse(policy.shouldCheck(start.plusSeconds(21601), false, false, false))
        assertFalse(policy.shouldCheck(start.plusSeconds(21601), true, true, false))
        assertFalse(policy.shouldCheck(start.plusSeconds(21601), true, false, true))
    }

    @Test fun failureKeepsSuccessfulTimestampAndBacksOff() {
        val policy = CheckPolicy()
        policy.succeeded(start)
        val failure = start.plusSeconds(21601)
        policy.failed(failure, false)
        assertEquals(start, policy.lastSuccess)
        assertFalse(policy.shouldCheck(failure.plusSeconds(899), true, false, false))
        assertTrue(policy.shouldCheck(failure.plusSeconds(900), true, false, false))
    }

    @Test fun loginExpiryDoesNotLoop() {
        val policy = CheckPolicy()
        policy.failed(start, true)
        assertFalse(policy.shouldCheck(start.plusSeconds(86400), true, false, false))
        policy.loginCompleted()
        assertTrue(policy.shouldCheck(start.plusSeconds(86400), true, false, false))
    }
}
