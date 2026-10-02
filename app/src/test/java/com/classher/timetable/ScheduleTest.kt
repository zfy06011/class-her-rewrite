package com.classher.timetable

import com.classher.timetable.domain.Arrangement
import com.classher.timetable.domain.MeetingTime
import com.classher.timetable.domain.SingleException
import com.classher.timetable.domain.Term
import com.classher.timetable.domain.TimeRange
import com.classher.timetable.domain.occurrences
import com.classher.timetable.domain.ranges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class ScheduleTest {
    private val term = Term(LocalDate.parse("2026-09-14"), 19)
    private val timetable = mapOf(
        1 to range("08:30", "09:15"), 2 to range("09:15", "10:00"),
        3 to range("10:20", "11:05"), 4 to range("11:05", "11:50"),
        5 to range("14:00", "14:45"),
    )
    private val arrangement = Arrangement(
        UUID.randomUUID(), "示例课程", "示例教师", "示例教室", 1, setOf(1, 3), MeetingTime.Periods(setOf(1, 2, 5)),
    )

    @Test fun weekBoundariesAndDates() {
        assertNull(term.weekOf(LocalDate.parse("2026-09-13")))
        assertEquals(1, term.weekOf(LocalDate.parse("2026-09-20")))
        assertEquals(2, term.weekOf(LocalDate.parse("2026-09-21")))
        assertEquals(LocalDate.parse("2027-01-24"), term.dateOf(19, 7))
        assertNull(term.weekOf(LocalDate.parse("2027-01-25")))
    }

    @Test fun onlyAdjacentPeriodsWithTouchingTimesJoin() {
        assertEquals(listOf(range("08:30", "10:00"), range("14:00", "14:45")), arrangement.time.ranges(timetable))
        assertEquals(2, MeetingTime.Periods(setOf(1, 2, 3, 4)).ranges(timetable).size)
    }

    @Test fun moveUsesOriginalIdentityAndLeavesOtherWeeks() {
        val original = term.dateOf(1, 1)
        val target = term.dateOf(2, 7)
        val move = SingleException.Move(arrangement.id, original, target, MeetingTime.Custom(range("06:30", "07:30")), "新教室")
        val result = occurrences(term, listOf(arrangement), listOf(move), timetable)
        assertEquals(2, result.size)
        assertEquals(original, result.first().originalDate)
        assertEquals(target, result.first().date)
        assertEquals(arrangement.id, result.first().arrangementId)
        assertEquals(term.dateOf(3, 1), result.last().date)
        val renamed = occurrences(term, listOf(arrangement.copy(name = "新名称")), listOf(move), timetable)
        assertTrue(renamed.all { it.name == "新名称" })
    }

    @Test fun cancelAffectsOnlyOneOccurrence() {
        val result = occurrences(term, listOf(arrangement), listOf(SingleException.Cancel(arrangement.id, term.dateOf(1, 1))), timetable)
        assertEquals(listOf(term.dateOf(3, 1)), result.map { it.date })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMoveOutsideTerm() {
        occurrences(term, listOf(arrangement), listOf(SingleException.Move(
            arrangement.id, term.dateOf(1, 1), term.lastDate.plusDays(1), arrangement.time, "",
        )), timetable)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOrphanException() {
        occurrences(term, listOf(arrangement), listOf(SingleException.Cancel(arrangement.id, term.dateOf(2, 1))), timetable)
    }

    @Test fun halfOpenTimeAndOverlap() {
        val time = range("08:30", "09:15")
        assertTrue(time.contains(LocalTime.parse("08:30")))
        assertFalse(time.contains(LocalTime.parse("09:15")))
        assertFalse(time.overlaps(range("09:15", "10:00")))
        assertTrue(time.overlaps(range("09:00", "10:00")))
    }

    private fun range(start: String, end: String) = TimeRange(LocalTime.parse(start), LocalTime.parse(end))
}
