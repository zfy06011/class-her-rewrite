package com.classher.timetable.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class SchedulingGroup(val weekday: Int, val weeks: Set<Int>, val time: MeetingTime) {
    init { require(weekday in 1..7 && weeks.isNotEmpty() && weeks.all { it in 1..60 }) }
}

data class ImportPlan(
    val snapshot: SchoolSnapshot,
    val term: Term,
    val periods: Map<Int, TimeRange>,
    val acknowledgedDoubts: Set<Int>,
    val fetchedAt: Instant,
    val attachToLocalTerm: UUID? = null,
) {
    fun validate() {
        require(snapshot.scope.accountDigest.matches(Regex("[0-9a-f]{64}")))
        require(snapshot.scope.year.matches(Regex("[0-9]{4}")) && snapshot.scope.semester in setOf("0", "1"))
        require(snapshot.meetings.isNotEmpty() && snapshot.meetings.distinct().size == snapshot.meetings.size)
        require(periods.isNotEmpty() && periods.keys == (1..periods.size).toSet())
        require(periods.size <= 48)
        require(snapshot.unscheduledNames.all { it.isNotBlank() } && snapshot.unscheduledNames.distinct().size == snapshot.unscheduledNames.size)
        require(periods.toSortedMap().values.zipWithNext().all { (a, b) -> a.end <= b.start })
        require(acknowledgedDoubts.all { it in snapshot.doubts.indices })
        snapshot.doubts.forEachIndexed { index, doubt ->
            require(doubt.kind in setOf(DoubtKind.MISSING_ROOM, DoubtKind.UNSCHEDULED)) { "Source content needs repair" }
            require(index in acknowledgedDoubts) { "Confirm each missing source field" }
            if (doubt.kind == DoubtKind.MISSING_ROOM) require(doubt.meeting in snapshot.meetings && doubt.meeting?.room?.isBlank() == true)
            if (doubt.kind == DoubtKind.UNSCHEDULED) require(snapshot.unscheduledNames.isNotEmpty())
        }
        require(snapshot.meetings.filter { it.room.isBlank() }.all { meeting ->
            snapshot.doubts.any { it.kind == DoubtKind.MISSING_ROOM && it.meeting == meeting }
        }) { "Missing room needs explicit acknowledgement" }
        require(snapshot.unscheduledNames.isEmpty() || snapshot.doubts.any { it.kind == DoubtKind.UNSCHEDULED })
        snapshot.meetings.forEach { meeting ->
            require(meeting.name.isNotBlank() && meeting.teacher.isNotBlank() && meeting.weekday in 1..7)
            require(meeting.weeks.isNotEmpty() && meeting.weeks.all { it in 1..term.weekCount })
            require(meeting.periods.isNotEmpty() && meeting.periods.all { it in periods })
        }
    }
}

data class SavedSchedule(
    val id: UUID,
    val title: String,
    val scope: SourceScope?,
    val term: Term,
    val periods: Map<Int, TimeRange>,
    val arrangements: List<Arrangement>,
    val unscheduled: List<String>,
    val lastSuccessfulCheck: Instant?,
    val revision: Long = 1,
    val colors: Map<UUID, CourseColor> = emptyMap(),
    val origins: Map<UUID, CourseOrigin> = emptyMap(),
    val academicYear: String = scope?.year ?: term.firstMonday.year.toString(),
    val semester: String = scope?.semester ?: "0",
)

sealed interface ImportOutcome {
    data class Saved(val termId: UUID, val alreadySaved: Boolean) : ImportOutcome
    data object ChangedSourceNeedsReview : ImportOutcome
    data object DifferentAccount : ImportOutcome
    data object DifferentTermConfiguration : ImportOutcome
    data object LocalTermConfirmationRequired : ImportOutcome
}

interface ScheduleRepository {
    fun observeSchedules(): Flow<List<SavedSchedule>>
    suspend fun confirmImport(plan: ImportPlan): ImportOutcome
    suspend fun checkSource(expected: SourceScope?, fetch: suspend () -> SchoolSnapshot): CheckedSource
    suspend fun saveArrangement(termId: UUID, expectedRevision: Long, edit: ArrangementEdit): EditOutcome
    suspend fun createManualTerm(plan: ManualTermPlan): ManualTermOutcome
}

data class CheckedSource(val snapshot: SchoolSnapshot, val fetchedAt: Instant, val outcome: ImportOutcome?)

fun sdwuDefaultPeriods(): Map<Int, TimeRange> = listOf(
    "08:30" to "09:15", "09:15" to "10:00", "10:20" to "11:05", "11:05" to "11:50",
    "14:00" to "14:45", "14:45" to "15:30", "15:50" to "16:35", "16:35" to "17:20",
    "18:30" to "19:15", "19:15" to "20:00", "20:20" to "21:05", "21:05" to "21:50",
).mapIndexed { index, (start, end) -> index + 1 to TimeRange(LocalTime.parse(start), LocalTime.parse(end)) }.toMap()
