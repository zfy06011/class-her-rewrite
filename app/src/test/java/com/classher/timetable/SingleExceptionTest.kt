package com.classher.timetable

import com.classher.timetable.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class SingleExceptionTest {
    private val term = Term(LocalDate.parse("2026-09-14"), 19)
    private val course = Arrangement(UUID.randomUUID(), "示例课程", "", "", 1, setOf(1, 2), MeetingTime.Periods(setOf(1, 2)))
    @Test fun sameUuidOnTargetDateCountsAnotherOriginalOccurrenceAsConflict() {
        val original = term.dateOf(1, 1)
        val moved = SingleException.Move(course.id, original, term.dateOf(2, 1), MeetingTime.Periods(setOf(1)), "")
        val all = occurrences(term, listOf(course), listOf(moved), sdwuDefaultPeriods())
        assertEquals(2, all.size)
        assertEquals(1, conflictingOccurrencesFor(all, course.id, original).size)
        assertEquals(setOf(course.id), conflictingArrangementIds(all, course.id))
        assertEquals(term.dateOf(2, 1), conflictingOccurrencesFor(all, course.id, original).single().originalDate)
    }
    @Test fun originalMustBeScheduledAndTargetMustStayInTerm() {
        val good = SingleException.Move(course.id, term.dateOf(1, 1), term.dateOf(19, 7), MeetingTime.Periods(setOf(1)), "")
        validateSingleException(term, course, sdwuDefaultPeriods(), good)
        for (bad in listOf(good.copy(originalDate = term.dateOf(1, 2)), good.copy(date = term.lastDate.plusDays(1)), good.copy(time = MeetingTime.Periods(setOf(13))))) {
            try { validateSingleException(term, course, sdwuDefaultPeriods(), bad); fail("Invalid exception accepted") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
    @Test fun cancellationDoesNotBecomeAnUpcomingOrConflictingOccurrence() {
        val original = term.dateOf(1, 1)
        val all = occurrences(term, listOf(course), listOf(SingleException.Cancel(course.id, original)), sdwuDefaultPeriods())
        assertEquals(listOf(term.dateOf(2, 1)), all.map { it.originalDate })
        assertTrue(conflictingOccurrencesFor(all, course.id, original).isEmpty())
    }
}
