package com.classher.timetable.domain

import java.time.Instant
import java.util.UUID

data class SchoolReview(val id: UUID, val snapshot: SchoolSnapshot, val fetchedAt: Instant)

data class ReviewCandidate(val index: Int, val meeting: ParsedMeeting, val exactMatches: List<UUID>)

fun schoolReviewCandidates(review: SchoolReview, baselines: Map<UUID, Arrangement>): List<ReviewCandidate> =
    review.snapshot.meetings.mapIndexed { index, meeting ->
        ReviewCandidate(index, meeting, baselines.filterValues { old ->
            old.name == meeting.name && old.teacher == meeting.teacher && old.room == meeting.room &&
                old.weekday == meeting.weekday && old.weeks == meeting.weeks &&
                (old.time as? MeetingTime.Periods)?.numbers == meeting.periods
        }.keys.toList())
    }

enum class RemovedSchoolChoice { DELETE, KEEP_MANUAL }
enum class ReviewField { NAME, TEACHER, ROOM, SCHEDULE }
data class ReviewFieldKey(val candidate: Int, val field: ReviewField)
data class SchoolReviewPlan(
    val termId: UUID, val revision: Long, val reviewId: UUID,
    /** Every candidate is explicitly accounted for; null means a confirmed new arrangement. */
    val links: Map<Int, UUID?>,
    val removed: Map<UUID, RemovedSchoolChoice>,
    val choices: Map<ReviewFieldKey, ConflictChoice>,
    val acknowledgedDoubts: Set<Int>,
    val acknowledgedHistoryDuplicates: Set<Int> = emptySet(),
)
data class ReviewFieldComparison(val key: ReviewFieldKey, val previous: String, val local: String, val incoming: String)
data class ReviewedArrangement(val candidate: Int, val previousId: UUID?, val arrangement: Arrangement)
data class SchoolReviewPreview(val arrangements: List<ReviewedArrangement>, val conflicts: List<ReviewFieldComparison>)

fun ParsedMeeting.arrangement(id: UUID) = Arrangement(id, name, teacher, room, weekday, weeks, MeetingTime.Periods(periods))
fun Arrangement.schedulingGroup() = SchedulingGroup(weekday, weeks, time)

/** Compare against the committed baseline even after manual conversion. No fuzzy identity guesses. */
fun previewSchoolReview(saved: SavedSchedule, links: Map<Int, UUID?>, choices: Map<ReviewFieldKey, ConflictChoice>): SchoolReviewPreview {
    val review = requireNotNull(saved.schoolReview)
    val current = (saved.arrangements + saved.hiddenSchoolCourses.map { it.arrangement }).associateBy { it.id }
    val conflicts = mutableListOf<ReviewFieldComparison>()
    fun <T> value(index: Int, field: ReviewField, before: T, local: T, next: T): T {
        val merged = mergeField(before, if (before == local) LocalOverride.None else LocalOverride.Value(local), next)
        return when (merged) {
            is FieldMerge.Merged -> merged.visibleValue
            is FieldMerge.Conflict -> {
                val key = ReviewFieldKey(index, field)
                if (choices[key] == null) conflicts += ReviewFieldComparison(key, reviewValue(before), reviewValue(local), reviewValue(next))
                if (choices[key] == ConflictChoice.USE_SCHOOL) next else local
            }
        }
    }
    val result = review.snapshot.meetings.mapIndexedNotNull { index, incoming ->
        if (!links.containsKey(index)) return@mapIndexedNotNull null
        val id = links[index]
        val next = incoming.arrangement(id ?: UUID(0, index.toLong() + 1)) // preview only; repository allocates new UUID
        if (id == null) return@mapIndexedNotNull ReviewedArrangement(index, null, next)
        val before = requireNotNull(saved.schoolBaselines[id])
        val local = requireNotNull(current[id])
        val group = value(index, ReviewField.SCHEDULE, before.schedulingGroup(), local.schedulingGroup(), next.schedulingGroup())
        ReviewedArrangement(index, id, next.copy(
            name = value(index, ReviewField.NAME, before.name, local.name, next.name),
            teacher = value(index, ReviewField.TEACHER, before.teacher, local.teacher, next.teacher),
            room = value(index, ReviewField.ROOM, before.room, local.room, next.room),
            weekday = group.weekday, weeks = group.weeks, time = group.time,
        ))
    }
    return SchoolReviewPreview(result, conflicts)
}

private fun reviewValue(value: Any?): String = when (value) {
    is SchedulingGroup -> "星期${value.weekday} · 周${value.weeks.sorted().joinToString("、")} · " + when (val time = value.time) {
        is MeetingTime.Periods -> "第 ${time.numbers.sorted().joinToString("、")} 节"
        is MeetingTime.Custom -> "${time.range.start}–${time.range.end}"
    }
    else -> value?.toString()?.ifBlank { "未提供" } ?: "未提供"
}

sealed interface ReviewOutcome {
    data class Saved(val termId: UUID, val orphanedExceptions: Int) : ReviewOutcome
    data object Busy : ReviewOutcome
    data object Stale : ReviewOutcome
    data object Invalid : ReviewOutcome
}
