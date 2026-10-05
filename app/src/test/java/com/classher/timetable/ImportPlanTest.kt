package com.classher.timetable

import com.classher.timetable.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ImportPlanTest {
    private val meeting = ParsedMeeting("示例课程", "示例教师", "", 1, setOf(1, 2, 19), setOf(1, 2))
    private val snapshot = SchoolSnapshot(SourceScope(accountDigest("synthetic-account"), "2026", "0"),
        listOf(meeting), listOf("未排课示例"), listOf(
            ParseDoubt("地点尚未提供", kind = DoubtKind.MISSING_ROOM, meeting = meeting),
            ParseDoubt("未排课", kind = DoubtKind.UNSCHEDULED),
        ))
    private fun plan(acknowledged: Set<Int> = setOf(0, 1)) = ImportPlan(snapshot,
        Term(LocalDate.parse("2026-09-14"), 19), sdwuDefaultPeriods(), acknowledged, Instant.parse("2026-10-05T00:00:00Z"))

    @Test fun allMissingItemsMustBeIndividuallyAcknowledged() {
        plan().validate()
        rejects { plan(setOf(1)).validate() }
        rejects { plan(setOf(0)).validate() }
        rejects { plan(setOf(0, 1, 2)).validate() }
    }
    @Test fun unknownContentCannotBeAcknowledgedAway() {
        rejects { plan().copy(snapshot = snapshot.copy(doubts = snapshot.doubts + ParseDoubt("未知文本")),
            acknowledgedDoubts = setOf(0, 1, 2)).validate() }
    }
    @Test fun unlabelledMissingFieldsCannotBeSaved() {
        rejects { plan().copy(snapshot = snapshot.copy(doubts = emptyList()), acknowledgedDoubts = emptySet()).validate() }
        rejects { plan().copy(snapshot = snapshot.copy(doubts = listOf(ParseDoubt("缺地点", kind = DoubtKind.MISSING_ROOM, meeting = meeting.copy(name = "不存在"))))).validate() }
    }
    @Test fun outOfRangeWeeksAndOverlappingPeriodsFailBeforeWriting() {
        rejects { plan().copy(term = Term(LocalDate.parse("2026-09-14"), 18)).validate() }
        val overlapping = sdwuDefaultPeriods().toMutableMap().apply { this[2] = this[1]!! }
        rejects { plan().copy(periods = overlapping).validate() }
        rejects { plan().copy(periods = sdwuDefaultPeriods() - 2).validate() }
    }
    @Test fun schedulingGroupKeepsTimeModeAndWeekSetTogether() {
        val group = SchedulingGroup(1, setOf(1, 3), MeetingTime.Periods(setOf(1, 2)))
        val source = group.copy(weekday = 2)
        val local = group.copy(weeks = setOf(1, 4))
        val merged = mergeField(group, LocalOverride.Value(local), source)
        assertEquals(FieldMerge.Conflict(group, local, source), merged)
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid import accepted") } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
