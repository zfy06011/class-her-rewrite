package com.classher.timetable.data.local

import androidx.room.withTransaction
import com.classher.timetable.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class RoomScheduleRepository(private val database: ScheduleDatabase, private val gate: ScheduleWriteGate) : ScheduleRepository {
    private val dao get() = database.schedules()

    override fun observeSchedules(): Flow<List<SavedSchedule>> = dao.observe().map { rows -> rows.map { row ->
        val term = row.term
        SavedSchedule(
            UUID.fromString(term.id), "${term.year} 学年 · 第 ${term.semester.toInt() + 1} 学期",
            SourceScope(term.accountDigest, term.year, term.semester), Term(LocalDate.parse(term.firstMonday), term.weekCount),
            row.periods.associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) },
            row.projections.map { projection -> projection.fields.let { fields ->
                Arrangement(UUID.fromString(projection.id), fields.name, fields.teacher, fields.room, fields.weekday,
                    decodeSet(fields.weeks), MeetingTime.Periods(decodeSet(fields.periods)))
            } }.sortedWith(compareBy({ it.weekday }, { (it.time as MeetingTime.Periods).numbers.min() }, { it.name })),
            row.unscheduled.map { it.name }.sorted(), Instant.ofEpochMilli(term.checkedAt),
        )
    } }

    override suspend fun confirmImport(plan: ImportPlan): ImportOutcome {
        plan.validate()
        return gate.write { database.withTransaction { store(plan) } }
    }

    override suspend fun checkSource(expected: SourceScope?, fetch: suspend () -> SchoolSnapshot): CheckedSource = gate.write {
        // 公共锁覆盖获取到检查提交；短数据库事务从网络结束后才开始。
        val snapshot = fetch()
        val fetchedAt = Instant.now()
        if (expected != null && snapshot.scope != expected) throw SchoolException(SchoolFailure.IDENTITY_MISMATCH)
        val outcome = database.withTransaction {
            val terms = dao.terms()
            if (terms.any { it.accountDigest != snapshot.scope.accountDigest }) return@withTransaction ImportOutcome.DifferentAccount
            val old = terms.singleOrNull { it.year == snapshot.scope.year && it.semester == snapshot.scope.semester }
                ?: return@withTransaction null
            if (snapshot.doubts.any { it.kind == DoubtKind.OTHER }) return@withTransaction null
            val periods = dao.periods(old.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
            val plan = ImportPlan(snapshot, Term(LocalDate.parse(old.firstMonday), old.weekCount), periods,
                snapshot.doubts.indices.toSet(), fetchedAt)
            plan.validate()
            // store 对已存在学期只在完整基线一致时推进时间，不创建新确认。
            store(plan)
        }
        CheckedSource(snapshot, fetchedAt, outcome)
    }

    private suspend fun store(plan: ImportPlan): ImportOutcome {
        val snapshot = plan.snapshot
        val terms = dao.terms()
        // 首版只绑定一个学校账号；切换不会覆盖或混入原课程。
        if (terms.any { it.accountDigest != snapshot.scope.accountDigest }) return ImportOutcome.DifferentAccount
        val old = terms.singleOrNull { it.year == snapshot.scope.year && it.semester == snapshot.scope.semester }
        if (old != null) {
            if (old.firstMonday != plan.term.firstMonday.toString() || old.weekCount != plan.term.weekCount ||
                dao.periods(old.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) } != plan.periods
            ) return ImportOutcome.DifferentTermConfiguration
            val before = dao.baselines(old.id).map { it.fields }.toSet()
            val after = snapshot.meetings.map(::fields).toSet()
            if (before != after || dao.unscheduled(old.id).map { it.name }.toSet() != snapshot.unscheduledNames.toSet()) {
                return ImportOutcome.ChangedSourceNeedsReview
            }
            dao.checked(old.id, maxOf(old.checkedAt, plan.fetchedAt.toEpochMilli()))
            return ImportOutcome.Saved(UUID.fromString(old.id), alreadySaved = true)
        }
        val termId = UUID.randomUUID().toString()
        dao.insertTerm(TermEntity(id = termId, accountDigest = snapshot.scope.accountDigest,
            year = snapshot.scope.year, semester = snapshot.scope.semester,
            firstMonday = plan.term.firstMonday.toString(), weekCount = plan.term.weekCount,
            checkedAt = plan.fetchedAt.toEpochMilli()))
        dao.insertPeriods(plan.periods.map { (number, time) -> PeriodEntity(termId, number, time.start.toString(), time.end.toString()) })
        val ids = snapshot.meetings.associateWith { UUID.randomUUID().toString() }
        dao.insertIdentities(ids.values.map { IdentityEntity(it, termId) })
        dao.insertBaselines(ids.map { (meeting, id) -> BaselineEntity(id, fields(meeting)) })
        dao.insertProjections(ids.map { (meeting, id) -> ProjectionEntity(id, termId, fields(meeting)) })
        dao.insertAcknowledgements(ids.filterKeys { it.room.isBlank() }.values.map { AcknowledgementEntity(it, "missing_room") })
        dao.insertUnscheduled(snapshot.unscheduledNames.map { UnscheduledEntity(UUID.randomUUID().toString(), termId, it) })
        return ImportOutcome.Saved(UUID.fromString(termId), alreadySaved = false)
    }

    private fun fields(meeting: ParsedMeeting) = MeetingFields(meeting.name, meeting.teacher, meeting.room,
        meeting.weekday, meeting.weeks.sorted().joinToString(","), meeting.periods.sorted().joinToString(","))
    private fun decodeSet(value: String): Set<Int> = value.split(",").map(String::toInt).toSet()
}
