package com.classher.timetable.domain

import java.util.UUID

enum class CourseColor { PINK, BLUE, YELLOW, MINT, PURPLE }
enum class CourseOrigin { SCHOOL, MANUAL }

data class ManualTermPlan(val academicYear: String, val semester: String, val term: Term, val periods: Map<Int, TimeRange>) {
    fun validate() {
        require(academicYear.matches(Regex("[0-9]{4}")) && semester in setOf("0", "1"))
        require(periods.isNotEmpty() && periods.size <= 48 && periods.keys == (1..periods.size).toSet())
        require(periods.toSortedMap().values.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
}
sealed interface ManualTermOutcome {
    data class Saved(val termId: UUID) : ManualTermOutcome
    data object DifferentConfiguration : ManualTermOutcome
    data object Busy : ManualTermOutcome
    data object Invalid : ManualTermOutcome
}

data class ArrangementEdit(
    val id: UUID?, val name: String, val teacher: String, val room: String,
    val scheduling: SchedulingGroup, val color: CourseColor = CourseColor.PINK,
) {
    fun validate(term: Term, periods: Map<Int, TimeRange>) {
        require(name.isNotBlank() && name.length <= 200 && teacher.length <= 200 && room.length <= 200)
        require(scheduling.weeks.all { it <= term.weekCount })
        scheduling.time.ranges(periods)
    }
    fun arrangement(id: UUID) = Arrangement(id, name.trim(), teacher.trim(), room.trim(), scheduling.weekday, scheduling.weeks, scheduling.time)
}

sealed interface EditOutcome {
    data class Saved(val arrangementId: UUID, val overlappingArrangements: Int) : EditOutcome
    data object Busy : EditOutcome
    data object Stale : EditOutcome
    data object Missing : EditOutcome
    data object Invalid : EditOutcome
    data object ExceptionReviewRequired : EditOutcome
}

/** 范围、单双周最终均保存为明确整数集合；不自动截断越界值。 */
fun parseIndexSet(text: String, limit: Int): Set<Int> {
    require(limit in 1..60)
    val normalized = text.trim().replace('，', ',').replace('、', ',').replace(" ", "")
    require(normalized.isNotEmpty())
    return normalized.split(',').flatMap { part ->
        require(part.matches(Regex("[0-9]+(-[0-9]+)?")))
        val bounds = part.split('-').map(String::toInt)
        val start = bounds.first(); val end = bounds.last()
        require(start in 1..limit && end in start..limit)
        (start..end).toList()
    }.toSet()
}

fun overlappingArrangementIds(candidate: Arrangement, others: List<Arrangement>, periods: Map<Int, TimeRange>): Set<UUID> =
    others.filter { other -> other.id != candidate.id && other.weekday == candidate.weekday &&
        other.weeks.any { it in candidate.weeks } && candidate.time.ranges(periods).any { range -> other.time.ranges(periods).any(range::overlaps) }
    }.map { it.id }.toSet()
