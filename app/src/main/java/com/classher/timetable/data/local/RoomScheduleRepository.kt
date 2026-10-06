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

    override fun observeSchedules(): Flow<List<SavedSchedule>> = dao.observe().map { rows -> rows.map(::saved) }
    private fun saved(row: TermBundle): SavedSchedule {
        val term = row.term
        val periods = row.periods.associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
        val modelTerm = Term(LocalDate.parse(term.firstMonday), term.weekCount)
        val visible = row.projections.filter { projection -> row.identities.any { it.id == projection.id && !it.hidden } }.map(::arrangement)
        val decoded = row.exceptions.filter { source -> visible.any { it.id.toString() == source.arrangementId } }.map(::singleException)
        val (associated, orphaned) = decoded.partition { exception ->
            visible.any { it.id == exception.arrangementId && runCatching { validateSingleException(modelTerm, it, periods, exception) }.isSuccess }
        }
        val hidden = row.identities.filter { it.hidden }.map { identity ->
            HiddenSchoolCourse(arrangement(row.projections.single { it.id == identity.id }),
                arrangement(identity.id, row.baselines.single { it.id == identity.id }.fields), CourseColor.entries[identity.colorSlot],
                row.exceptions.filter { it.arrangementId == identity.id }.map(::singleException))
        }
        return SavedSchedule(
            UUID.fromString(term.id), "${term.year} 学年 · 第 ${term.semester.toInt() + 1} 学期${if (term.school == "local") "（手工）" else ""}",
            if (term.school == "local") null else SourceScope(term.accountDigest, term.year, term.semester), Term(LocalDate.parse(term.firstMonday), term.weekCount),
            periods,
            visible.sortedWith(compareBy({ it.weekday }, { it.time.ranges(periods).first().start }, { it.name })),
            row.unscheduled.map { it.name }.sorted(), if (term.school == "local") null else Instant.ofEpochMilli(term.checkedAt),
            term.revision, row.identities.associate { UUID.fromString(it.id) to CourseColor.entries[it.colorSlot] },
            row.identities.associate { UUID.fromString(it.id) to when (it.origin) { "school" -> CourseOrigin.SCHOOL; "manual" -> CourseOrigin.MANUAL; else -> error("Unsupported saved course origin") } },
            term.year, term.semester,
            associated, orphaned,
            hidden,
            row.reviews.singleOrNull()?.let { SchoolReview(UUID.fromString(it.id), SchoolSnapshotCodec.decode(it.payload), Instant.ofEpochMilli(it.fetchedAt)) },
            row.baselines.associate { UUID.fromString(it.id) to arrangement(it.id, it.fields) },
        )
    }

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
            if (terms.any { it.school == "sdwu" && it.accountDigest != snapshot.scope.accountDigest }) return@withTransaction ImportOutcome.DifferentAccount
            val old = terms.singleOrNull { it.school == "sdwu" && it.year == snapshot.scope.year && it.semester == snapshot.scope.semester }
                ?: return@withTransaction null
            if (snapshot.doubts.any { it.kind == DoubtKind.OTHER }) return@withTransaction null
            val periods = dao.periods(old.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
            val plan = ImportPlan(snapshot, Term(LocalDate.parse(old.firstMonday), old.weekCount), periods,
                snapshot.doubts.indices.toSet(), fetchedAt)
            plan.validate()
            // 完整有效结果保存为基线或待核对候选；不把获取失败算作成功检查。
            store(plan)
        }
        CheckedSource(snapshot, fetchedAt, outcome)
    }

    private suspend fun store(plan: ImportPlan): ImportOutcome {
        val snapshot = plan.snapshot
        val terms = dao.terms()
        // 首版只绑定一个学校账号；切换不会覆盖或混入原课程。
        if (terms.any { it.school == "sdwu" && it.accountDigest != snapshot.scope.accountDigest }) return ImportOutcome.DifferentAccount
        val old = terms.singleOrNull { it.school == "sdwu" && it.year == snapshot.scope.year && it.semester == snapshot.scope.semester }
        if (old != null) {
            if (plan.fetchedAt.toEpochMilli() < old.checkedAt) return ImportOutcome.StaleSnapshot
            if (old.firstMonday != plan.term.firstMonday.toString() || old.weekCount != plan.term.weekCount ||
                dao.periods(old.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) } != plan.periods
            ) return ImportOutcome.DifferentTermConfiguration
            val previous = dao.review(old.id)
            if (previous != null && plan.fetchedAt.toEpochMilli() < previous.fetchedAt) return ImportOutcome.StaleSnapshot
            val before = dao.baselines(old.id).map { it.fields }.toSet()
            val after = snapshot.meetings.map(::fields).toSet()
            if (before != after || dao.unscheduled(old.id).map { it.name }.toSet() != snapshot.unscheduledNames.toSet()) {
                if (previous != null && SchoolSnapshotCodec.decode(previous.payload).sameContent(snapshot)) {
                    dao.reviewChecked(previous.id, maxOf(previous.fetchedAt, plan.fetchedAt.toEpochMilli()))
                } else {
                    dao.clearReview(old.id)
                    dao.insertReview(SchoolReviewEntity(UUID.randomUUID().toString(), old.id, plan.fetchedAt.toEpochMilli(), SchoolSnapshotCodec.encode(snapshot)))
                }
                dao.checked(old.id, maxOf(old.checkedAt, plan.fetchedAt.toEpochMilli()))
                return ImportOutcome.ChangedSourceNeedsReview
            }
            dao.clearReview(old.id)
            dao.checked(old.id, maxOf(old.checkedAt, plan.fetchedAt.toEpochMilli()))
            return ImportOutcome.Saved(UUID.fromString(old.id), alreadySaved = true)
        }
        val local = terms.singleOrNull { it.school == "local" && it.year == snapshot.scope.year && it.semester == snapshot.scope.semester }
        if (local != null) {
            if (local.firstMonday != plan.term.firstMonday.toString() || local.weekCount != plan.term.weekCount ||
                dao.periods(local.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) } != plan.periods
            ) return ImportOutcome.DifferentTermConfiguration
            if (plan.attachToLocalTerm?.toString() != local.id) return ImportOutcome.LocalTermConfirmationRequired
        }
        val termId = local?.id ?: UUID.randomUUID().toString()
        if (local != null) dao.bindSchool(termId, snapshot.scope.accountDigest, plan.fetchedAt.toEpochMilli())
        else {
            dao.insertTerm(TermEntity(id = termId, accountDigest = snapshot.scope.accountDigest,
                year = snapshot.scope.year, semester = snapshot.scope.semester,
                firstMonday = plan.term.firstMonday.toString(), weekCount = plan.term.weekCount,
                checkedAt = plan.fetchedAt.toEpochMilli()))
            dao.insertPeriods(plan.periods.map { (number, time) -> PeriodEntity(termId, number, time.start.toString(), time.end.toString()) })
        }
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

    private fun arrangement(projection: ProjectionEntity): Arrangement {
        return arrangement(projection.id, projection.fields)
    }
    private fun arrangement(id: String, fields: MeetingFields): Arrangement {
        val time = when (fields.timeMode) {
            "periods" -> MeetingTime.Periods(decodeSet(fields.periods))
            "custom" -> MeetingTime.Custom(TimeRange(LocalTime.parse(fields.customStart), LocalTime.parse(fields.customEnd)))
            else -> error("Unsupported saved time mode")
        }
        return Arrangement(UUID.fromString(id), fields.name, fields.teacher, fields.room, fields.weekday, decodeSet(fields.weeks), time)
    }

    private fun fields(arrangement: Arrangement): MeetingFields = when (val time = arrangement.time) {
        is MeetingTime.Periods -> MeetingFields(arrangement.name, arrangement.teacher, arrangement.room,
            arrangement.weekday, arrangement.weeks.sorted().joinToString(","), time.numbers.sorted().joinToString(","))
        is MeetingTime.Custom -> MeetingFields(arrangement.name, arrangement.teacher, arrangement.room,
            arrangement.weekday, arrangement.weeks.sorted().joinToString(","), "", "custom", time.range.start.toString(), time.range.end.toString())
    }

    override suspend fun saveArrangement(termId: UUID, expectedRevision: Long, edit: ArrangementEdit): EditOutcome {
        try { return gate.tryWrite { database.withTransaction {
            val term = dao.terms().singleOrNull { it.id == termId.toString() } ?: return@withTransaction EditOutcome.Missing
            if (term.revision != expectedRevision) return@withTransaction EditOutcome.Stale
            val periods = dao.periods(term.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
            edit.validate(Term(LocalDate.parse(term.firstMonday), term.weekCount), periods)
            val id = edit.id ?: UUID.randomUUID()
            val old = if (edit.id == null) null else dao.identity(id.toString())
            if (edit.id != null && (old == null || old.termId != term.id || old.hidden)) return@withTransaction EditOutcome.Missing
            val candidate = edit.arrangement(id)
            val newFields = fields(candidate)
            val projections = dao.projections(term.id)
            val previous = projections.singleOrNull { it.id == id.toString() }
            if (old != null && previous == null) return@withTransaction EditOutcome.Missing
            // 不让整条修改静默遗失已有单次例外的关联。具体调课界面另行实现。
            if (previous != null && dao.exceptions(id.toString()).isNotEmpty() &&
                (previous.fields.weekday != newFields.weekday || previous.fields.weeks != newFields.weeks ||
                    previous.fields.periods != newFields.periods || previous.fields.timeMode != newFields.timeMode ||
                    previous.fields.customStart != newFields.customStart || previous.fields.customEnd != newFields.customEnd)
            ) return@withTransaction EditOutcome.ExceptionReviewRequired
            val visibleIds = dao.identities(term.id).filterNot { it.hidden }.map { it.id }.toSet()
            val finalArrangements = projections.filter { it.id in visibleIds && it.id != id.toString() }.map(::arrangement) + candidate
            val activeExceptions = dao.termExceptions(term.id).map(::singleException).filter { value ->
                finalArrangements.any { it.id == value.arrangementId && runCatching { validateSingleException(Term(LocalDate.parse(term.firstMonday), term.weekCount), it, periods, value) }.isSuccess }
            }
            val expanded = occurrences(Term(LocalDate.parse(term.firstMonday), term.weekCount), finalArrangements, activeExceptions, periods)
            val conflicts = conflictingArrangementIds(expanded, id).size
            if (old == null) dao.insertIdentities(listOf(IdentityEntity(id.toString(), term.id, origin = "manual", colorSlot = edit.color.ordinal)))
            else {
                if (old.origin == "school") {
                    val baseline = dao.baselines(term.id).single { it.id == id.toString() }.fields
                    val scheduleChanged = baseline.weekday != newFields.weekday || baseline.weeks != newFields.weeks || baseline.periods != newFields.periods ||
                        baseline.timeMode != newFields.timeMode || baseline.customStart != newFields.customStart || baseline.customEnd != newFields.customEnd
                    dao.putOverride(OverrideEntity(id.toString(),
                        name = newFields.name.takeUnless { it == baseline.name }, teacher = newFields.teacher.takeUnless { it == baseline.teacher },
                        room = newFields.room.takeUnless { it == baseline.room }, weekday = newFields.weekday.takeIf { scheduleChanged },
                        weeks = newFields.weeks.takeIf { scheduleChanged }, timeMode = newFields.timeMode.takeIf { scheduleChanged },
                        periods = newFields.periods.takeIf { scheduleChanged }, customStart = newFields.customStart.takeIf { scheduleChanged },
                        customEnd = newFields.customEnd.takeIf { scheduleChanged }))
                }
                dao.color(id.toString(), edit.color.ordinal)
            }
            if (old == null || old.origin == "manual") dao.putManual(ManualEntity(id.toString(), newFields))
            dao.putProjection(ProjectionEntity(id.toString(), term.id, newFields))
            dao.edited(term.id)
            EditOutcome.Saved(id, conflicts)
        } } } catch (_: ScheduleBusyException) { return EditOutcome.Busy } catch (_: IllegalArgumentException) { return EditOutcome.Invalid }
    }

    override suspend fun createManualTerm(plan: ManualTermPlan): ManualTermOutcome {
        try {
            plan.validate()
            return gate.tryWrite { database.withTransaction {
                val existing = dao.terms().singleOrNull { it.year == plan.academicYear && it.semester == plan.semester }
                if (existing != null) {
                    val periods = dao.periods(existing.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
                    if (existing.firstMonday != plan.term.firstMonday.toString() || existing.weekCount != plan.term.weekCount || periods != plan.periods)
                        return@withTransaction ManualTermOutcome.DifferentConfiguration
                    return@withTransaction ManualTermOutcome.Saved(UUID.fromString(existing.id))
                }
                val id = UUID.randomUUID().toString()
                dao.insertTerm(TermEntity(id = id, school = "local", accountDigest = "", year = plan.academicYear,
                    semester = plan.semester, firstMonday = plan.term.firstMonday.toString(), weekCount = plan.term.weekCount, checkedAt = 0))
                dao.insertPeriods(plan.periods.map { (number, range) -> PeriodEntity(id, number, range.start.toString(), range.end.toString()) })
                ManualTermOutcome.Saved(UUID.fromString(id))
            } }
        } catch (_: ScheduleBusyException) { return ManualTermOutcome.Busy } catch (_: IllegalArgumentException) { return ManualTermOutcome.Invalid }
    }

    private fun singleException(row: ExceptionEntity): SingleException {
        val id = UUID.fromString(row.arrangementId); val date = LocalDate.parse(row.originalDate)
        return when (row.kind) {
            "cancel" -> SingleException.Cancel(id, date)
            "move" -> {
                val time = when (row.timeMode) {
                    "periods" -> MeetingTime.Periods(decodeSet(requireNotNull(row.periods)))
                    "custom" -> MeetingTime.Custom(TimeRange(LocalTime.parse(row.customStart), LocalTime.parse(row.customEnd)))
                    else -> error("Unsupported exception time mode")
                }
                SingleException.Move(id, date, LocalDate.parse(row.targetDate), time, requireNotNull(row.room))
            }
            else -> error("Unsupported exception kind")
        }
    }

    private fun exceptionRow(value: SingleException): ExceptionEntity = when (value) {
        is SingleException.Cancel -> ExceptionEntity(value.arrangementId.toString(), value.originalDate.toString(), "cancel")
        is SingleException.Move -> when (val time = value.time) {
            is MeetingTime.Periods -> ExceptionEntity(value.arrangementId.toString(), value.originalDate.toString(), "move",
                value.date.toString(), "periods", time.numbers.sorted().joinToString(","), room = value.room)
            is MeetingTime.Custom -> ExceptionEntity(value.arrangementId.toString(), value.originalDate.toString(), "move",
                value.date.toString(), "custom", customStart = time.range.start.toString(), customEnd = time.range.end.toString(), room = value.room)
        }
    }

    override suspend fun saveSingleException(termId: UUID, expectedRevision: Long, exception: SingleException): EditOutcome =
        writeSingle(termId, expectedRevision, exception.arrangementId, exception.originalDate, exception)

    override suspend fun clearSingleException(termId: UUID, expectedRevision: Long, arrangementId: UUID, originalDate: LocalDate): EditOutcome =
        writeSingle(termId, expectedRevision, arrangementId, originalDate, null)

    private suspend fun writeSingle(termId: UUID, expectedRevision: Long, id: UUID, originalDate: LocalDate, next: SingleException?): EditOutcome {
        try { return gate.tryWrite { database.withTransaction {
            val term = dao.terms().singleOrNull { it.id == termId.toString() } ?: return@withTransaction EditOutcome.Missing
            if (term.revision != expectedRevision) return@withTransaction EditOutcome.Stale
            val identity = dao.identity(id.toString()) ?: return@withTransaction EditOutcome.Missing
            if (identity.termId != term.id || identity.hidden) return@withTransaction EditOutcome.Missing
            val projections = dao.projections(term.id)
            val course = projections.singleOrNull { it.id == id.toString() }?.let(::arrangement) ?: return@withTransaction EditOutcome.Missing
            val periods = dao.periods(term.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
            val modelTerm = Term(LocalDate.parse(term.firstMonday), term.weekCount)
            if (next != null) {
                validateSingleException(modelTerm, course, periods, next)
                dao.putException(exceptionRow(next))
            } else {
                if (dao.exceptions(id.toString()).none { it.originalDate == originalDate.toString() }) return@withTransaction EditOutcome.Missing
                dao.clearException(id.toString(), originalDate.toString())
            }
            val visibleIds = dao.identities(term.id).filterNot { it.hidden }.map { it.id }.toSet()
            val arrangements = projections.filter { it.id in visibleIds }.map(::arrangement)
            val active = dao.termExceptions(term.id).map(::singleException).filter { value ->
                arrangements.any { it.id == value.arrangementId && runCatching { validateSingleException(modelTerm, it, periods, value) }.isSuccess }
            }
            val all = occurrences(modelTerm, arrangements, active, periods)
            val conflicts = conflictingOccurrencesFor(all, id, originalDate).size
            dao.edited(term.id)
            EditOutcome.Saved(id, conflicts)
        } } } catch (_: ScheduleBusyException) { return EditOutcome.Busy } catch (_: IllegalArgumentException) { return EditOutcome.Invalid }
    }

    override suspend fun removeArrangement(termId: UUID, expectedRevision: Long, arrangementId: UUID): EditOutcome =
        changeVisibility(termId, expectedRevision, arrangementId, restore = false)
    override suspend fun restoreArrangement(termId: UUID, expectedRevision: Long, arrangementId: UUID): EditOutcome =
        changeVisibility(termId, expectedRevision, arrangementId, restore = true)

    override suspend fun confirmSchoolReview(plan: SchoolReviewPlan): ReviewOutcome {
        try { return gate.tryWrite { database.withTransaction {
            val bundle = dao.bundle(plan.termId.toString()) ?: return@withTransaction ReviewOutcome.Stale
            val current = saved(bundle)
            val review = current.schoolReview ?: return@withTransaction ReviewOutcome.Stale
            if (review.id != plan.reviewId || current.revision != plan.revision) return@withTransaction ReviewOutcome.Stale
            require(review.snapshot.scope == current.scope)
            ImportPlan(review.snapshot, current.term, current.periods, plan.acknowledgedDoubts, review.fetchedAt).validate()
            require(plan.links.keys == review.snapshot.meetings.indices.toSet())
            val linked = plan.links.values.filterNotNull()
            require(linked.distinct().size == linked.size && linked.all { it in current.schoolBaselines })
            val active = current.origins.filterValues { it == CourseOrigin.SCHOOL }.keys
            val activeBaselines = current.schoolBaselines.filterKeys { it in active }
            val candidates = schoolReviewCandidates(review, activeBaselines)
            candidates.forEach { candidate ->
                if (candidate.exactMatches.size == 1) require(plan.links[candidate.index] == candidate.exactMatches.single())
                if (plan.links[candidate.index] == null) {
                    val history = schoolReviewCandidates(review, current.schoolBaselines.filterKeys { it !in active })[candidate.index]
                    require(history.exactMatches.isEmpty() || candidate.index in plan.acknowledgedHistoryDuplicates)
                }
            }
            require(plan.removed.keys == active - linked.toSet())
            val raw = previewSchoolReview(current, plan.links, emptyMap())
            require(plan.choices.keys == raw.conflicts.map { it.key }.toSet())
            val preview = previewSchoolReview(current, plan.links, plan.choices)
            require(preview.conflicts.isEmpty())
            // All validation completes before writes. Conversion retains baseline as inactive history.
            plan.removed.forEach { (id, choice) ->
                when (choice) {
                    RemovedSchoolChoice.DELETE -> dao.deleteIdentity(id.toString())
                    RemovedSchoolChoice.KEEP_MANUAL -> {
                        val projected = bundle.projections.single { it.id == id.toString() }
                        dao.putManual(ManualEntity(projected.id, projected.fields))
                        dao.origin(projected.id, "manual")
                        dao.clearOverride(projected.id)
                        dao.clearAcknowledgements(projected.id)
                    }
                }
            }
            preview.arrangements.forEach { value ->
                val id = value.previousId ?: UUID.randomUUID()
                val incoming = review.snapshot.meetings[value.candidate].arrangement(id)
                val effective = value.arrangement.copy(id = id)
                val next = fields(effective); val baseline = fields(incoming)
                if (value.previousId == null) dao.insertIdentities(listOf(IdentityEntity(id.toString(), bundle.term.id)))
                else { dao.origin(id.toString(), "school"); dao.clearManual(id.toString()) }
                dao.putBaseline(BaselineEntity(id.toString(), baseline))
                dao.putProjection(ProjectionEntity(id.toString(), bundle.term.id, next))
                val scheduleChanged = effective.schedulingGroup() != incoming.schedulingGroup()
                dao.putOverride(OverrideEntity(id.toString(), next.name.takeUnless { it == baseline.name },
                    next.teacher.takeUnless { it == baseline.teacher }, next.room.takeUnless { it == baseline.room },
                    next.weekday.takeIf { scheduleChanged }, next.weeks.takeIf { scheduleChanged }, next.timeMode.takeIf { scheduleChanged },
                    next.periods.takeIf { scheduleChanged }, next.customStart.takeIf { scheduleChanged }, next.customEnd.takeIf { scheduleChanged }))
                dao.clearAcknowledgements(id.toString())
                if (incoming.room.isBlank()) dao.insertAcknowledgements(listOf(AcknowledgementEntity(id.toString(), "missing_room")))
            }
            dao.clearUnscheduled(bundle.term.id)
            dao.insertUnscheduled(review.snapshot.unscheduledNames.map { UnscheduledEntity(UUID.randomUUID().toString(), bundle.term.id, it) })
            dao.checked(bundle.term.id, maxOf(bundle.term.checkedAt, review.fetchedAt.toEpochMilli()))
            dao.edited(bundle.term.id)
            dao.clearReview(bundle.term.id)
            val after = saved(requireNotNull(dao.bundle(bundle.term.id)))
            ReviewOutcome.Saved(current.id, after.orphanedExceptions.size)
        } } } catch (_: ScheduleBusyException) { return ReviewOutcome.Busy } catch (_: IllegalArgumentException) { return ReviewOutcome.Invalid }
    }

    private suspend fun changeVisibility(termId: UUID, expectedRevision: Long, id: UUID, restore: Boolean): EditOutcome {
        try { return gate.tryWrite { database.withTransaction {
            val term = dao.terms().singleOrNull { it.id == termId.toString() } ?: return@withTransaction EditOutcome.Missing
            if (term.revision != expectedRevision) return@withTransaction EditOutcome.Stale
            val identity = dao.identity(id.toString()) ?: return@withTransaction EditOutcome.Missing
            if (identity.termId != term.id) return@withTransaction EditOutcome.Missing
            if (restore) {
                if (dao.review(term.id) != null) return@withTransaction EditOutcome.ExceptionReviewRequired
                if (!identity.hidden) return@withTransaction EditOutcome.Missing
                val projected = dao.projections(term.id).singleOrNull { it.id == identity.id } ?: return@withTransaction EditOutcome.Missing
                if (dao.baselineHistory(term.id).none { it.id == identity.id }) return@withTransaction EditOutcome.Missing
                // 当前已提交投影包含保留覆盖；恢复只改变隐藏标记。
                arrangement(projected).time.ranges(dao.periods(term.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) })
                dao.hide(identity.id, false)
            } else when (identity.origin) {
                "school" -> { if (identity.hidden) return@withTransaction EditOutcome.Missing; dao.hide(identity.id, true) }
                "manual" -> dao.deleteIdentity(identity.id)
                else -> return@withTransaction EditOutcome.Invalid
            }
            var conflicts = 0
            if (restore) {
                val periods = dao.periods(term.id).associate { it.number to TimeRange(LocalTime.parse(it.start), LocalTime.parse(it.end)) }
                val modelTerm = Term(LocalDate.parse(term.firstMonday), term.weekCount)
                val visibleIds = dao.identities(term.id).filterNot { it.hidden }.map { it.id }.toSet()
                val courses = dao.projections(term.id).filter { it.id in visibleIds }.map(::arrangement)
                val exceptions = dao.termExceptions(term.id).map(::singleException).filter { exception ->
                    courses.any { it.id == exception.arrangementId && runCatching { validateSingleException(modelTerm, it, periods, exception) }.isSuccess }
                }
                conflicts = conflictingArrangementIds(occurrences(modelTerm, courses, exceptions, periods), id).size
            }
            dao.edited(term.id)
            EditOutcome.Saved(id, conflicts)
        } } } catch (_: ScheduleBusyException) { return EditOutcome.Busy } catch (_: IllegalArgumentException) { return EditOutcome.Invalid }
    }
}
