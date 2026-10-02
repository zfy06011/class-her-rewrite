package com.classher.timetable.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class LessonSegment(val occurrence: Occurrence, val range: TimeRange)

data class TodayEntry(val occurrence: Occurrence, val ended: Boolean, val conflicting: Boolean)

data class UpcomingLesson(val segment: LessonSegment, val startsAt: Instant, val untilStart: Duration)

data class TodaySummary(
    val date: LocalDate,
    val teachingWeek: Int?,
    val entries: List<TodayEntry>,
    val running: List<LessonSegment>,
    val next: UpcomingLesson?,
)

/** 输入是本地已提交课表的例外应用结果；不读取学校网页，不写入数据。 */
fun summarizeToday(term: Term, occurrences: List<Occurrence>, now: Instant): TodaySummary {
    require(occurrences.all { term.weekOf(it.date) != null && it.ranges.isNotEmpty() })
    val schoolNow = now.atZone(SchoolZone)
    val date = schoolNow.toLocalDate()
    val time = schoolNow.toLocalTime()
    val today = occurrences.filter { it.date == date }
        .sortedWith(compareBy({ it.ranges.minOf { range -> range.start } }, { it.arrangementId.toString() }, { it.originalDate }))
    val entries = today.mapIndexed { index, occurrence ->
        TodayEntry(
            occurrence = occurrence,
            ended = occurrence.ranges.all { it.end <= time },
            conflicting = today.withIndex().any { (otherIndex, other) ->
                otherIndex != index && occurrence.ranges.any { range -> other.ranges.any(range::overlaps) }
            },
        )
    }
    val running = today.flatMap { occurrence ->
        occurrence.ranges.filter { it.contains(time) }.map { LessonSegment(occurrence, it) }
    }
    // 非连续节次分别作为候选，不能把中间休息时间视为正在上课。
    val next = occurrences.asSequence().flatMap { occurrence ->
        occurrence.ranges.asSequence().map { range ->
            val start = occurrence.date.atTime(range.start).atZone(SchoolZone).toInstant()
            UpcomingLesson(LessonSegment(occurrence, range), start, Duration.between(now, start))
        }
    }.filter { it.startsAt > now }
        .minWithOrNull(compareBy({ it.startsAt }, { it.segment.occurrence.arrangementId.toString() }, { it.segment.occurrence.originalDate }))
    return TodaySummary(date, term.weekOf(date), entries, running, next)
}
