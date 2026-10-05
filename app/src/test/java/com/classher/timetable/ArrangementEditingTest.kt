package com.classher.timetable

import com.classher.timetable.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class ArrangementEditingTest {
    @Test fun explicitRangesAndChineseSeparatorsDoNotInferMissingWeeks() {
        assertEquals(setOf(1, 3, 4, 5, 8), parseIndexSet("1，3-5、8", 19))
        for (text in listOf("", "0", "20", "5-3", "1,,2", "1-3 单", "-1")) {
            try { parseIndexSet(text, 19); fail("Invalid range accepted") } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
    @Test fun gapBetweenSelectedPeriodsDoesNotBecomeAConflict() {
        val periods = sdwuDefaultPeriods()
        val candidate = Arrangement(UUID.randomUUID(), "示例课程", "", "", 1, setOf(1), MeetingTime.Periods(setOf(1, 2, 5)))
        val gap = candidate.copy(id = UUID.randomUUID(), time = MeetingTime.Custom(TimeRange(LocalTime.parse("11:00"), LocalTime.parse("12:00"))))
        val overlap = gap.copy(id = UUID.randomUUID(), time = MeetingTime.Custom(TimeRange(LocalTime.parse("09:30"), LocalTime.parse("10:30"))))
        val differentWeek = overlap.copy(id = UUID.randomUUID(), weeks = setOf(2))
        assertEquals(setOf(overlap.id), overlappingArrangementIds(candidate, listOf(gap, overlap, differentWeek), periods))
    }
    @Test fun manualCustomTimeHasNoMandatoryTeacherOrRoom() {
        val term = Term(LocalDate.parse("2026-09-14"), 19)
        ArrangementEdit(null, "示例课程", "", "", SchedulingGroup(7, setOf(1, 19), MeetingTime.Custom(TimeRange(LocalTime.parse("07:00"), LocalTime.parse("08:00"))))).validate(term, sdwuDefaultPeriods())
        try {
            ArrangementEdit(null, "示例课程", "", "", SchedulingGroup(1, setOf(20), MeetingTime.Periods(setOf(1)))).validate(term, sdwuDefaultPeriods())
            fail("Out of term accepted")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
