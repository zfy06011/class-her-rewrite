package com.classher.timetable

import com.classher.timetable.domain.Occurrence
import com.classher.timetable.domain.SchoolZone
import com.classher.timetable.domain.Term
import com.classher.timetable.domain.TimeRange
import com.classher.timetable.domain.summarizeToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class TodaySummaryTest {
    private val term = Term(LocalDate.parse("2026-09-14"), 19)

    @Test fun schoolZoneDeterminesDateAndWeekFromInstant() {
        val summary = summarizeToday(term, emptyList(), java.time.Instant.parse("2026-09-13T16:30:00Z"))
        assertEquals(LocalDate.parse("2026-09-14"), summary.date)
        assertEquals(1, summary.teachingWeek)
    }

    @Test fun startsAreInclusiveEndsExclusiveAndTouchingClassesDoNotConflict() {
        val lessons = listOf(lesson(1, "2026-09-14", "08:30", "09:15"), lesson(2, "2026-09-14", "09:15", "10:00"))
        val summary = summarizeToday(term, lessons, instant("2026-09-14T09:15"))
        assertTrue(summary.entries.first().ended)
        assertFalse(summary.entries.last().ended)
        assertTrue(summary.entries.none { it.conflicting })
        assertEquals(lessons.last().arrangementId, summary.running.single().occurrence.arrangementId)
        assertNull(summary.next)
    }

    @Test fun overlapsRemainVisibleAndBothAreMarked() {
        val lessons = listOf(lesson(1, "2026-09-14", "08:30", "09:30"), lesson(2, "2026-09-14", "09:00", "10:00"))
        val summary = summarizeToday(term, lessons, instant("2026-09-14T09:10"))
        assertEquals(2, summary.entries.size)
        assertTrue(summary.entries.all { it.conflicting })
        assertEquals(2, summary.running.size)
    }

    @Test fun emptyDayFindsNextFutureDayAndCountdown() {
        val next = lesson(1, "2026-09-16", "08:30", "09:15")
        val summary = summarizeToday(term, listOf(next), instant("2026-09-15T08:30"))
        assertTrue(summary.entries.isEmpty())
        assertEquals(next, summary.next?.segment?.occurrence)
        assertEquals(Duration.ofHours(24), summary.next?.untilStart)
    }

    @Test fun gapBetweenNonContinuousSegmentsIsNotRunningOrEnded() {
        val base = lesson(1, "2026-09-14", "08:30", "10:00")
        val separated = base.copy(ranges = base.ranges + TimeRange(LocalTime.parse("14:00"), LocalTime.parse("14:45")))
        val summary = summarizeToday(term, listOf(separated), instant("2026-09-14T12:00"))
        assertTrue(summary.running.isEmpty())
        assertFalse(summary.entries.single().ended)
        assertEquals(LocalTime.parse("14:00"), summary.next?.segment?.range?.start)
        assertEquals(Duration.ofHours(2), summary.next?.untilStart)
    }

    @Test fun movedOccurrencesUseTargetDayAndMayConflictWithSameCourse() {
        val first = lesson(1, "2026-09-21", "08:30", "09:15")
        val moved = first.copy(originalDate = LocalDate.parse("2026-09-14"), adjusted = true)
        val summary = summarizeToday(term, listOf(first, moved), instant("2026-09-21T08:45"))
        assertEquals(2, summary.entries.size)
        assertTrue(summary.entries.all { it.conflicting })
        assertEquals(2, summary.running.size)
    }

    @Test fun afterTermHasNoTeachingWeekOrNextLesson() {
        val summary = summarizeToday(term, listOf(lesson(1, "2026-09-14", "08:30", "09:15")), instant("2027-01-25T08:30"))
        assertNull(summary.teachingWeek)
        assertNull(summary.next)
        assertTrue(summary.entries.isEmpty())
    }

    private fun instant(value: String) = LocalDateTime.parse(value).atZone(SchoolZone).toInstant()

    private fun lesson(id: Long, date: String, start: String, end: String): Occurrence {
        val day = LocalDate.parse(date)
        return Occurrence(UUID(0, id), day, day, "示例课程", "示例教室",
            listOf(TimeRange(LocalTime.parse(start), LocalTime.parse(end))), false)
    }
}
