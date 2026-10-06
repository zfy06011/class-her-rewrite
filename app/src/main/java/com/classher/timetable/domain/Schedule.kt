package com.classher.timetable.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

val SchoolZone: ZoneId = ZoneId.of("Asia/Shanghai")

data class Term(val firstMonday: LocalDate, val weekCount: Int) {
    init {
        require(firstMonday.dayOfWeek == DayOfWeek.MONDAY)
        require(weekCount in 1..60)
    }
    val lastDate: LocalDate get() = firstMonday.plusWeeks(weekCount.toLong()).minusDays(1)
    fun weekOf(date: LocalDate): Int? = if (date in firstMonday..lastDate) {
        (ChronoUnit.DAYS.between(firstMonday, date) / 7).toInt() + 1
    } else null
    fun dateOf(week: Int, weekday: Int): LocalDate {
        require(week in 1..weekCount && weekday in 1..7)
        return firstMonday.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())
    }
}

data class TimeRange(val start: LocalTime, val end: LocalTime) {
    init { require(start < end) }
    fun contains(time: LocalTime): Boolean = time >= start && time < end
    fun overlaps(other: TimeRange): Boolean = start < other.end && other.start < end
}

sealed interface MeetingTime {
    data class Periods(val numbers: Set<Int>) : MeetingTime {
        init { require(numbers.isNotEmpty() && numbers.all { it in 1..48 }) }
    }
    data class Custom(val range: TimeRange) : MeetingTime
}

fun MeetingTime.ranges(timetable: Map<Int, TimeRange>): List<TimeRange> = when (this) {
    is MeetingTime.Custom -> listOf(range)
    is MeetingTime.Periods -> {
        val selected = numbers.sorted().map { it to requireNotNull(timetable[it]) }
        val result = mutableListOf<TimeRange>()
        var previousNumber: Int? = null
        for ((number, range) in selected) {
            val last = result.lastOrNull()
            // 只有相邻节次且时间首尾相接才能合成连续区间。
            if (last != null && previousNumber == number - 1 && last.end == range.start) {
                result[result.lastIndex] = TimeRange(last.start, range.end)
            } else result += range
            previousNumber = number
        }
        result
    }
}

data class Arrangement(
    val id: UUID,
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,
    val weeks: Set<Int>,
    val time: MeetingTime,
) {
    init {
        require(name.isNotBlank() && weekday in 1..7)
        require(weeks.isNotEmpty() && weeks.all { it in 1..60 })
    }
}

sealed interface SingleException {
    val arrangementId: UUID
    val originalDate: LocalDate
    data class Cancel(
        override val arrangementId: UUID,
        override val originalDate: LocalDate,
    ) : SingleException
    data class Move(
        override val arrangementId: UUID,
        override val originalDate: LocalDate,
        val date: LocalDate,
        val time: MeetingTime,
        val room: String,
    ) : SingleException
}

data class Occurrence(
    val arrangementId: UUID,
    val originalDate: LocalDate,
    val date: LocalDate,
    val name: String,
    val room: String,
    val ranges: List<TimeRange>,
    val adjusted: Boolean,
)

fun validateSingleException(term: Term, arrangement: Arrangement, periods: Map<Int, TimeRange>, exception: SingleException) {
    require(exception.arrangementId == arrangement.id)
    require(term.weekOf(exception.originalDate) in arrangement.weeks && exception.originalDate.dayOfWeek.value == arrangement.weekday)
    if (exception is SingleException.Move) {
        require(term.weekOf(exception.date) != null && exception.room.length <= 200)
        exception.time.ranges(periods)
    }
}

fun conflictingOccurrencesFor(all: List<Occurrence>, arrangementId: UUID, originalDate: LocalDate): List<Occurrence> {
    val target = all.singleOrNull { it.arrangementId == arrangementId && it.originalDate == originalDate } ?: return emptyList()
    return all.filter { other -> (other.arrangementId != arrangementId || other.originalDate != originalDate) &&
        other.date == target.date && target.ranges.any { range -> other.ranges.any(range::overlaps) }
    }
}

fun conflictingArrangementIds(all: List<Occurrence>, arrangementId: UUID): Set<UUID> = all.filter { it.arrangementId == arrangementId }
    .flatMap { conflictingOccurrencesFor(all, arrangementId, it.originalDate) }.map { it.arrangementId }.toSet()

fun occurrences(
    term: Term,
    arrangements: List<Arrangement>,
    exceptions: List<SingleException>,
    timetable: Map<Int, TimeRange>,
): List<Occurrence> {
    require(arrangements.map { it.id }.distinct().size == arrangements.size)
    val index = arrangements.associateBy { it.id }
    val byOriginal = exceptions.associateBy { it.arrangementId to it.originalDate }
    require(byOriginal.size == exceptions.size)
    for (exception in exceptions) {
        val arrangement = requireNotNull(index[exception.arrangementId])
        require(term.weekOf(exception.originalDate) in arrangement.weeks)
        require(exception.originalDate.dayOfWeek.value == arrangement.weekday)
        if (exception is SingleException.Move) require(term.weekOf(exception.date) != null)
    }
    return arrangements.flatMap { arrangement ->
        require(arrangement.weeks.all { it <= term.weekCount })
        arrangement.weeks.sorted().mapNotNull { week ->
            val original = term.dateOf(week, arrangement.weekday)
            when (val exception = byOriginal[arrangement.id to original]) {
                is SingleException.Cancel -> null
                is SingleException.Move -> Occurrence(
                    arrangement.id, original, exception.date, arrangement.name,
                    exception.room, exception.time.ranges(timetable), true,
                )
                null -> Occurrence(
                    arrangement.id, original, original, arrangement.name,
                    arrangement.room, arrangement.time.ranges(timetable), false,
                )
            }
        }
    }.sortedWith(compareBy({ it.date }, { it.ranges.first().start }, { it.name }))
}
