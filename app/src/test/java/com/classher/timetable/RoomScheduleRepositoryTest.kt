package com.classher.timetable

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.classher.timetable.data.local.RoomScheduleRepository
import com.classher.timetable.data.local.ScheduleDatabase
import com.classher.timetable.data.local.MIGRATION_1_2
import com.classher.timetable.data.local.MIGRATION_2_3
import com.classher.timetable.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RoomScheduleRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ScheduleDatabase
    private lateinit var repository: RoomScheduleRepository
    private val gate = ScheduleWriteGate()
    private val meeting = ParsedMeeting("示例课程", "示例教师", "", 1, setOf(1, 2), setOf(1, 2))
    private val plan = ImportPlan(
        SchoolSnapshot(SourceScope(accountDigest("synthetic-account"), "2026", "0"), listOf(meeting), listOf("未排课示例"),
            listOf(ParseDoubt("缺地点", kind = DoubtKind.MISSING_ROOM, meeting = meeting), ParseDoubt("未排课", kind = DoubtKind.UNSCHEDULED))),
        Term(LocalDate.parse("2026-09-14"), 19), sdwuDefaultPeriods(), setOf(0, 1), Instant.parse("2026-10-05T00:00:00Z"),
    )

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("import-test.db")
        reopen()
    }
    private fun reopen() {
        database = Room.databaseBuilder(context, ScheduleDatabase::class.java, "import-test.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        repository = RoomScheduleRepository(database, gate)
    }
    @After fun close() { database.close(); context.deleteDatabase("import-test.db") }

    @Test fun diskReopenKeepsIdentityMissingRoomAndUnscheduledList() = runBlocking {
        val outcome = repository.confirmImport(plan) as ImportOutcome.Saved
        val before = repository.observeSchedules().first().single()
        assertEquals(outcome.termId, before.id)
        assertEquals("", before.arrangements.single().room)
        assertEquals(listOf("未排课示例"), before.unscheduled)
        assertEquals(1, before.arrangements.size)
        database.close(); reopen()
        assertEquals(before, repository.observeSchedules().first().single())
    }
    @Test fun identicalImportPreservesUuidsAndOnlyAdvancesSuccessTime() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        val later = plan.fetchedAt.plusSeconds(60)
        val outcome = repository.confirmImport(plan.copy(fetchedAt = later)) as ImportOutcome.Saved
        assertTrue(outcome.alreadySaved)
        val after = repository.observeSchedules().first().single()
        assertEquals(before.copy(lastSuccessfulCheck = later), after)
        assertEquals(1, database.schedules().baselines(before.id.toString()).size)
    }
    @Test fun changedSourceAndDifferentAccountDoNotOverwriteCommittedData() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        // 非缺失字段的变更不自动关联；测试不改原有缺地点确认引用。
        val changed = meeting.copy(teacher = "另一示例教师")
        val changedSnapshot = plan.snapshot.copy(meetings = listOf(changed), doubts = listOf(
            ParseDoubt("缺地点", kind = DoubtKind.MISSING_ROOM, meeting = changed), ParseDoubt("未排课", kind = DoubtKind.UNSCHEDULED)))
        assertEquals(ImportOutcome.ChangedSourceNeedsReview, repository.confirmImport(plan.copy(snapshot = changedSnapshot)))
        val otherAccount = plan.snapshot.copy(scope = plan.snapshot.scope.copy(accountDigest = accountDigest("another-synthetic-account")))
        assertEquals(ImportOutcome.DifferentAccount, repository.confirmImport(plan.copy(snapshot = otherAccount)))
        val pending = repository.observeSchedules().first().single()
        assertNotNull(pending.schoolReview)
        assertEquals(before, pending.copy(schoolReview = null))
    }
    @Test fun unconfirmedDoubtAndChangedConfigurationLeaveDatabaseIntact() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        try { repository.confirmImport(plan.copy(acknowledgedDoubts = setOf(0))); fail("Missing acknowledgement accepted") }
        catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals(ImportOutcome.DifferentTermConfiguration, repository.confirmImport(plan.copy(term = Term(LocalDate.parse("2026-09-21"), 19))))
        assertEquals(before, repository.observeSchedules().first().single())
    }
    @Test fun failedProjectionInsertRollsBackTermBaselineAndIdentityTogether() = runBlocking(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_projection BEFORE INSERT ON projections BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        try { repository.confirmImport(plan); fail("Injected write failure ignored") }
        catch (_: Exception) { /* SQLite failure must roll the whole transaction back. */ }
        assertTrue(repository.observeSchedules().first().isEmpty())
        assertTrue(database.schedules().terms().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_projection")
        assertTrue(repository.confirmImport(plan) is ImportOutcome.Saved)
    }
    @Test fun concurrentIdenticalImportsCreateOnlyOneCommittedTerm() = runBlocking {
        // 公共入口拒绝并发写入；若第二次在第一次提交后开始，则按相同基线幂等处理。
        val results = List(2) { async(Dispatchers.IO) { try { repository.confirmImport(plan) } catch (_: ScheduleBusyException) { null } } }.awaitAll()
        assertEquals(1, results.filterIsInstance<ImportOutcome.Saved>().count { !it.alreadySaved })
        assertEquals(1, repository.observeSchedules().first().size)
        assertEquals(1, repository.observeSchedules().first().single().arrangements.size)
    }
    @Test fun fetchingNewTermDoesNotImportWithoutConfirmation() = runBlocking {
        val checked = repository.checkSource(null) { plan.snapshot }
        assertNull(checked.outcome)
        assertEquals(plan.snapshot, checked.snapshot)
        assertTrue(repository.observeSchedules().first().isEmpty())
    }
    @Test fun successfulCheckRequiresMatchingScopeAndCompleteKnownSource() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        val unknown = plan.snapshot.copy(doubts = plan.snapshot.doubts + ParseDoubt("未知文本"))
        assertNull(repository.checkSource(plan.snapshot.scope) { unknown }.outcome)
        assertEquals(before, repository.observeSchedules().first().single())
        try {
            repository.checkSource(plan.snapshot.scope) { plan.snapshot.copy(scope = plan.snapshot.scope.copy(semester = "1")) }
            fail("Mismatched semester accepted")
        } catch (error: SchoolException) { assertEquals(SchoolFailure.IDENTITY_MISMATCH, error.failure) }
        assertEquals(before, repository.observeSchedules().first().single())
        assertTrue(repository.checkSource(plan.snapshot.scope) { plan.snapshot }.outcome is ImportOutcome.Saved)
        assertEquals(before.arrangements, repository.observeSchedules().first().single().arrangements)
    }
    @Test fun cancelledFetchReleasesSharedWriteGateWithoutDatabaseChanges() = runBlocking {
        try {
            withTimeout(30) { repository.checkSource(null) { delay(10_000); plan.snapshot } }
            fail("Fetch did not cancel")
        } catch (_: TimeoutCancellationException) { /* expected */ }
        assertTrue(repository.observeSchedules().first().isEmpty())
        assertTrue(withTimeout(5_000) { repository.confirmImport(plan) } is ImportOutcome.Saved)
    }
    @Test fun schoolEditKeepsUuidBaselineAndSeparateAtomicOverride() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        val id = before.arrangements.single().id
        val edit = ArrangementEdit(id, "编辑后课程", "编辑后教师", "编辑后地点", SchedulingGroup(2, setOf(1, 3), MeetingTime.Custom(TimeRange(java.time.LocalTime.parse("07:00"), java.time.LocalTime.parse("08:00")))), CourseColor.MINT)
        assertTrue(repository.saveArrangement(before.id, before.revision, edit) is EditOutcome.Saved)
        val after = repository.observeSchedules().first().single()
        assertEquals(edit.arrangement(id), after.arrangements.single())
        assertEquals(CourseColor.MINT, after.colors[id])
        assertEquals(meeting.teacher, database.schedules().baselines(before.id.toString()).single().fields.teacher)
        val local = database.schedules().overrides(id.toString())!!
        assertEquals(2, local.weekday); assertEquals("1,3", local.weeks); assertEquals("custom", local.timeMode)
        assertEquals("", local.periods); assertEquals("07:00", local.customStart)
        repository.confirmImport(plan)
        assertEquals(after.arrangements, repository.observeSchedules().first().single().arrangements)
        database.close(); reopen()
        assertEquals(after.arrangements, repository.observeSchedules().first().single().arrangements)
    }
    @Test fun explicitEmptyOverrideIsDistinctFromReturningToSchoolValue() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single()
        val course = saved.arrangements.single()
        val clear = ArrangementEdit(course.id, course.name, "", course.room, SchedulingGroup(course.weekday, course.weeks, course.time))
        assertTrue(repository.saveArrangement(saved.id, saved.revision, clear) is EditOutcome.Saved)
        assertEquals("", database.schedules().overrides(course.id.toString())!!.teacher)
        saved = repository.observeSchedules().first().single()
        assertTrue(repository.saveArrangement(saved.id, saved.revision, clear.copy(teacher = course.teacher)) is EditOutcome.Saved)
        assertNull(database.schedules().overrides(course.id.toString())!!.teacher)
    }
    @Test fun staleEditorCannotReplaceNewerCommittedValues() = runBlocking {
        repository.confirmImport(plan)
        val saved = repository.observeSchedules().first().single(); val course = saved.arrangements.single()
        val edit = ArrangementEdit(course.id, course.name, "最新教师", course.room, SchedulingGroup(course.weekday, course.weeks, course.time))
        repository.saveArrangement(saved.id, saved.revision, edit)
        assertEquals(EditOutcome.Stale, repository.saveArrangement(saved.id, saved.revision, edit.copy(teacher = "旧页面教师")))
        assertEquals("最新教师", repository.observeSchedules().first().single().arrangements.single().teacher)
    }
    @Test fun manualArrangementNeverJoinsSchoolBaselineAndSurvivesCheck() = runBlocking {
        repository.confirmImport(plan)
        val saved = repository.observeSchedules().first().single()
        val edit = ArrangementEdit(null, "手工示例", "", "", SchedulingGroup(1, setOf(1), MeetingTime.Periods(setOf(1))), CourseColor.BLUE)
        val result = repository.saveArrangement(saved.id, saved.revision, edit) as EditOutcome.Saved
        assertEquals(1, result.overlappingArrangements)
        assertEquals(1, database.schedules().baselines(saved.id.toString()).size)
        assertEquals("手工示例", database.schedules().manual(result.arrangementId.toString())!!.fields.name)
        repository.checkSource(plan.snapshot.scope) { plan.snapshot }
        val after = repository.observeSchedules().first().single()
        assertEquals(2, after.arrangements.size); assertEquals(CourseOrigin.MANUAL, after.origins[result.arrangementId])
        assertEquals(CourseColor.BLUE, after.colors[result.arrangementId])
    }
    @Test fun invalidEditCannotPartiallyCommitProjectionOrOverride() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        val invalid = ArrangementEdit(course.id, "新名字", "", "", SchedulingGroup(1, setOf(20), MeetingTime.Periods(setOf(1))))
        assertEquals(EditOutcome.Invalid, repository.saveArrangement(before.id, before.revision, invalid))
        assertNull(database.schedules().overrides(course.id.toString()))
        assertEquals(before, repository.observeSchedules().first().single())
    }
    @Test fun repositoryRejectsEditingDuringFetchWhileCommittedCoursesStayReadable() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val check = async { repository.checkSource(plan.snapshot.scope) { started.complete(Unit); finish.await(); plan.snapshot } }
        started.await()
        try {
            val edit = ArrangementEdit(course.id, "不会写入", "", "", SchedulingGroup(course.weekday, course.weeks, course.time))
            assertEquals(EditOutcome.Busy, repository.saveArrangement(before.id, before.revision, edit))
            assertEquals(before, repository.observeSchedules().first().single())
        } finally { finish.complete(Unit); check.await() }
        assertTrue(repository.saveArrangement(before.id, before.revision, ArrangementEdit(course.id, course.name, course.teacher, course.room, SchedulingGroup(course.weekday, course.weeks, course.time))) is EditOutcome.Saved)
    }
    @Test fun manualTermNeedsNoSourceAccountAndSurvivesDiskReopen() = runBlocking {
        val result = repository.createManualTerm(ManualTermPlan("2026", "0", plan.term, plan.periods)) as ManualTermOutcome.Saved
        val before = repository.observeSchedules().first().single()
        assertNull(before.scope); assertNull(before.lastSuccessfulCheck)
        val edit = ArrangementEdit(null, "离线示例", "", "", SchedulingGroup(1, setOf(1), MeetingTime.Periods(setOf(1))))
        repository.saveArrangement(result.termId, before.revision, edit)
        val after = repository.observeSchedules().first().single()
        database.close(); reopen()
        assertEquals(after, repository.observeSchedules().first().single())
    }
    @Test fun attachingSchoolRequiresConfirmationAndPreservesManualTermUuidAndCourses() = runBlocking {
        val result = repository.createManualTerm(ManualTermPlan("2026", "0", plan.term, plan.periods)) as ManualTermOutcome.Saved
        var saved = repository.observeSchedules().first().single()
        val manual = repository.saveArrangement(result.termId, saved.revision, ArrangementEdit(null, "手工保留", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1))), CourseColor.PURPLE)) as EditOutcome.Saved
        saved = repository.observeSchedules().first().single()
        assertEquals(ImportOutcome.LocalTermConfirmationRequired, repository.confirmImport(plan))
        assertEquals(ImportOutcome.DifferentTermConfiguration, repository.confirmImport(plan.copy(term = Term(LocalDate.parse("2026-09-21"), 19), attachToLocalTerm = result.termId)))
        assertEquals(saved, repository.observeSchedules().first().single())
        val linked = repository.confirmImport(plan.copy(attachToLocalTerm = result.termId)) as ImportOutcome.Saved
        assertEquals(result.termId, linked.termId)
        val after = repository.observeSchedules().first().single()
        assertEquals(plan.snapshot.scope, after.scope); assertEquals(2, after.arrangements.size)
        assertTrue(after.arrangements.any { it.id == manual.arrangementId })
        assertEquals(CourseOrigin.MANUAL, after.origins[manual.arrangementId]); assertEquals(CourseColor.PURPLE, after.colors[manual.arrangementId])
    }
    @Test fun repeatedManualTermCreationDoesNotCreateDuplicateAcademicTerm() = runBlocking {
        val manual = ManualTermPlan("2026", "0", plan.term, plan.periods)
        val first = repository.createManualTerm(manual)
        assertEquals(first, repository.createManualTerm(manual))
        assertEquals(ManualTermOutcome.DifferentConfiguration, repository.createManualTerm(manual.copy(term = Term(LocalDate.parse("2026-09-21"), 19))))
        assertEquals(1, repository.observeSchedules().first().size)
    }
    @Test fun failedEditProjectionUpdateRollsBackOverrideColorAndRevision() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_edit BEFORE UPDATE ON projections BEGIN SELECT RAISE(ABORT, 'synthetic edit failure'); END")
        val edit = ArrangementEdit(course.id, "新课程名", "新教师", "新地点", SchedulingGroup(course.weekday, course.weeks, course.time), CourseColor.BLUE)
        try { repository.saveArrangement(before.id, before.revision, edit); fail("Injected edit failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertNull(database.schedules().overrides(course.id.toString()))
        assertEquals(before, repository.observeSchedules().first().single())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_edit")
    }
    @Test fun courseUuidCannotBeEditedThroughAnotherTerm() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        val other = repository.createManualTerm(ManualTermPlan("2025", "1", Term(LocalDate.parse("2026-03-02"), 19), plan.periods)) as ManualTermOutcome.Saved
        val edit = ArrangementEdit(course.id, "不会跨学期写入", "", "", SchedulingGroup(course.weekday, course.weeks, course.time))
        assertEquals(EditOutcome.Missing, repository.saveArrangement(other.termId, 1, edit))
        assertEquals(before, repository.observeSchedules().first().single { it.id == before.id })
    }
    @Test fun failedManualInsertLeavesNoBusinessRowOrIdentity() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_manual_projection BEFORE INSERT ON projections BEGIN SELECT RAISE(ABORT, 'synthetic manual failure'); END")
        val edit = ArrangementEdit(null, "未提交手工示例", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1))))
        try { repository.saveArrangement(before.id, before.revision, edit); fail("Injected manual failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertEquals(before, repository.observeSchedules().first().single())
        assertEquals(1, database.schedules().identities(before.id.toString()).size)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM manual_arrangements").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_manual_projection")
    }
    @Test fun singleMoveKeepsRegularProjectionAndSchoolBaselineUnchanged() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        val move = SingleException.Move(course.id, plan.term.dateOf(1, 1), plan.term.dateOf(1, 3), MeetingTime.Periods(setOf(3, 4)), "单次地点")
        assertTrue(repository.saveSingleException(before.id, before.revision, move) is EditOutcome.Saved)
        val after = repository.observeSchedules().first().single()
        assertEquals(before.arrangements, after.arrangements); assertEquals(listOf(move), after.exceptions)
        assertEquals(meeting.teacher, database.schedules().baselines(before.id.toString()).single().fields.teacher)
        assertNull(database.schedules().overrides(course.id.toString()))
        val expanded = occurrences(after.term, after.arrangements, after.exceptions, after.periods)
        assertEquals(listOf(move.date, plan.term.dateOf(2, 1)), expanded.map { it.date })
    }
    @Test fun cancellationPersistsAndCanBeUndoneAfterReopen() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val cancel = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, cancel)
        database.close(); reopen()
        saved = repository.observeSchedules().first().single()
        assertEquals(listOf(cancel), saved.exceptions)
        assertEquals(1, occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods).size)
        assertTrue(repository.clearSingleException(saved.id, saved.revision, id, cancel.originalDate) is EditOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertTrue(saved.exceptions.isEmpty())
        assertEquals(2, occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods).size)
    }
    @Test fun editingMovedDateUsesOriginalKeyAndReplacesSameRow() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val move = SingleException.Move(id, plan.term.dateOf(1, 1), plan.term.dateOf(2, 1), MeetingTime.Periods(setOf(1)), "")
        assertEquals(1, (repository.saveSingleException(saved.id, saved.revision, move) as EditOutcome.Saved).overlappingArrangements)
        saved = repository.observeSchedules().first().single()
        val changed = move.copy(date = plan.term.dateOf(1, 7), time = MeetingTime.Custom(TimeRange(java.time.LocalTime.parse("18:00"), java.time.LocalTime.parse("19:00"))))
        repository.saveSingleException(saved.id, saved.revision, changed)
        assertEquals(1, database.schedules().exceptions(id.toString()).size)
        assertEquals(listOf(changed), repository.observeSchedules().first().single().exceptions)
    }
    @Test fun twoOriginalDatesCanMoveToSameDayWithoutCollapsingIdentity() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val target = plan.term.dateOf(1, 3)
        repository.saveSingleException(saved.id, saved.revision, SingleException.Move(id, plan.term.dateOf(1, 1), target, MeetingTime.Periods(setOf(1)), ""))
        saved = repository.observeSchedules().first().single()
        val result = repository.saveSingleException(saved.id, saved.revision, SingleException.Move(id, plan.term.dateOf(2, 1), target, MeetingTime.Periods(setOf(1)), "")) as EditOutcome.Saved
        assertEquals(1, result.overlappingArrangements)
        saved = repository.observeSchedules().first().single()
        assertEquals(2, saved.exceptions.size)
        assertEquals(2, occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods).count { it.date == target })
    }
    @Test fun invalidSingleDatesAndPeriodsDoNotWriteAnyAdjustment() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val id = before.arrangements.single().id
        val good = SingleException.Move(id, plan.term.dateOf(1, 1), plan.term.dateOf(1, 3), MeetingTime.Periods(setOf(1)), "")
        for (bad in listOf(good.copy(originalDate = plan.term.dateOf(1, 2)), good.copy(date = plan.term.lastDate.plusDays(1)), good.copy(time = MeetingTime.Periods(setOf(13))))) {
            assertEquals(EditOutcome.Invalid, repository.saveSingleException(before.id, before.revision, bad))
        }
        assertEquals(before, repository.observeSchedules().first().single())
    }
    @Test fun staleSingleEditorCannotOverwriteNewCancellation() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val id = before.arrangements.single().id
        val cancel = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        repository.saveSingleException(before.id, before.revision, cancel)
        assertEquals(EditOutcome.Stale, repository.saveSingleException(before.id, before.revision, SingleException.Move(id, cancel.originalDate, plan.term.dateOf(1, 3), MeetingTime.Periods(setOf(1)), "")))
        assertEquals(listOf(cancel), repository.observeSchedules().first().single().exceptions)
    }
    @Test fun existingExceptionsSurviveTeacherEditAndBlockSilentRecurrenceChanges() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val course = saved.arrangements.single()
        val cancel = SingleException.Cancel(course.id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, cancel)
        saved = repository.observeSchedules().first().single()
        val edit = ArrangementEdit(course.id, course.name, "修改后教师", course.room, SchedulingGroup(course.weekday, course.weeks, course.time))
        repository.saveArrangement(saved.id, saved.revision, edit)
        saved = repository.observeSchedules().first().single()
        assertEquals(listOf(cancel), saved.exceptions)
        assertEquals(EditOutcome.ExceptionReviewRequired, repository.saveArrangement(saved.id, saved.revision, edit.copy(scheduling = edit.scheduling.copy(weekday = 2))))
        assertEquals(saved, repository.observeSchedules().first().single())
    }
    @Test fun singleAdjustmentDuringFetchIsRejectedByCommonWriteGate() = runBlocking {
        repository.confirmImport(plan)
        val saved = repository.observeSchedules().first().single(); val course = saved.arrangements.single()
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val check = async { repository.checkSource(plan.snapshot.scope) { started.complete(Unit); finish.await(); plan.snapshot } }
        started.await()
        try { assertEquals(EditOutcome.Busy, repository.saveSingleException(saved.id, saved.revision, SingleException.Cancel(course.id, plan.term.dateOf(1, 1)))) }
        finally { finish.complete(Unit); check.await() }
        assertTrue(repository.observeSchedules().first().single().exceptions.isEmpty())
    }
    @Test fun failedSingleWriteRollsBackExceptionAndRevision() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_single BEFORE INSERT ON single_exceptions BEGIN SELECT RAISE(ABORT, 'synthetic single failure'); END")
        try { repository.saveSingleException(before.id, before.revision, SingleException.Cancel(course.id, plan.term.dateOf(1, 1))); fail("Injected exception failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertEquals(before, repository.observeSchedules().first().single())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_single")
    }
    @Test fun unmatchedOriginalDatesArePreservedAsReviewItemsAndCanBeCleared() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val date = plan.term.dateOf(1, 2)
        database.openHelper.writableDatabase.execSQL("INSERT INTO single_exceptions (arrangementId, originalDate, kind) VALUES (?, ?, 'cancel')", arrayOf(id.toString(), date.toString()))
        saved = repository.observeSchedules().first().single()
        assertTrue(saved.exceptions.isEmpty()); assertEquals(1, saved.orphanedExceptions.size)
        assertEquals(2, occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods).size)
        assertTrue(repository.clearSingleException(saved.id, saved.revision, id, date) is EditOutcome.Saved)
        assertTrue(repository.observeSchedules().first().single().orphanedExceptions.isEmpty())
    }
    @Test fun singleExceptionCannotUseAnotherTermOrMissingCourse() = runBlocking {
        repository.confirmImport(plan)
        val saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val other = repository.createManualTerm(ManualTermPlan("2025", "1", Term(LocalDate.parse("2026-03-02"), 19), plan.periods)) as ManualTermOutcome.Saved
        val cancel = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        assertEquals(EditOutcome.Missing, repository.saveSingleException(other.termId, 1, cancel))
        assertEquals(EditOutcome.Missing, repository.saveSingleException(saved.id, saved.revision, cancel.copy(arrangementId = java.util.UUID.randomUUID())))
        assertTrue(repository.observeSchedules().first().single { it.id == saved.id }.exceptions.isEmpty())
    }
    @Test fun repeatedSchoolCheckDoesNotRemoveSingleAdjustments() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val cancel = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, cancel)
        repository.checkSource(plan.snapshot.scope) { plan.snapshot }
        saved = repository.observeSchedules().first().single()
        assertEquals(listOf(cancel), saved.exceptions)
        assertEquals(1, occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods).size)
    }
    @Test fun schoolHideRetainsIdentityOverridesColorAndExceptionsWithoutResurrection() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val course = saved.arrangements.single()
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(course.id, course.name, "保留教师修改", "保留地点修改", SchedulingGroup(course.weekday, course.weeks, course.time), CourseColor.PURPLE))
        saved = repository.observeSchedules().first().single()
        val cancel = SingleException.Cancel(course.id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, cancel)
        saved = repository.observeSchedules().first().single()
        assertTrue(repository.removeArrangement(saved.id, saved.revision, course.id) is EditOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertTrue(saved.arrangements.isEmpty()); assertTrue(saved.exceptions.isEmpty())
        val hidden = saved.hiddenSchoolCourses.single()
        assertEquals(course.id, hidden.arrangement.id); assertEquals("保留教师修改", hidden.arrangement.teacher)
        assertEquals(course.teacher, hidden.schoolBaseline.teacher); assertEquals(CourseColor.PURPLE, hidden.color)
        assertEquals(listOf(cancel), hidden.exceptions)
        repository.checkSource(plan.snapshot.scope) { plan.snapshot }
        saved = repository.observeSchedules().first().single()
        assertTrue(saved.arrangements.isEmpty()); assertEquals(1, saved.hiddenSchoolCourses.size)
        database.close(); reopen()
        saved = repository.observeSchedules().first().single()
        repository.restoreArrangement(saved.id, saved.revision, course.id)
        saved = repository.observeSchedules().first().single()
        assertEquals(course.id, saved.arrangements.single().id); assertEquals("保留教师修改", saved.arrangements.single().teacher)
        assertEquals(CourseColor.PURPLE, saved.colors[course.id]); assertEquals(listOf(cancel), saved.exceptions)
        assertTrue(saved.hiddenSchoolCourses.isEmpty())
    }
    @Test fun deletingManualCourseCascadesBusinessProjectionAndSingleExceptionOnly() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single()
        val manual = repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "手工删除示例", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1))))) as EditOutcome.Saved
        saved = repository.observeSchedules().first().single()
        repository.saveSingleException(saved.id, saved.revision, SingleException.Cancel(manual.arrangementId, plan.term.dateOf(1, 2)))
        saved = repository.observeSchedules().first().single()
        repository.removeArrangement(saved.id, saved.revision, manual.arrangementId)
        assertNull(database.schedules().identity(manual.arrangementId.toString()))
        assertNull(database.schedules().manual(manual.arrangementId.toString()))
        assertTrue(database.schedules().exceptions(manual.arrangementId.toString()).isEmpty())
        saved = repository.observeSchedules().first().single()
        assertEquals(1, saved.arrangements.size); assertEquals(CourseOrigin.SCHOOL, saved.origins[saved.arrangements.single().id])
        assertEquals(1, database.schedules().terms().size)
    }
    @Test fun staleDeleteAndRestoreCannotChangeNewerCourseState() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val course = before.arrangements.single()
        repository.saveArrangement(before.id, before.revision, ArrangementEdit(course.id, course.name, "新教师", course.room, SchedulingGroup(course.weekday, course.weeks, course.time)))
        assertEquals(EditOutcome.Stale, repository.removeArrangement(before.id, before.revision, course.id))
        var saved = repository.observeSchedules().first().single()
        repository.removeArrangement(saved.id, saved.revision, course.id)
        saved = repository.observeSchedules().first().single(); val stale = saved.revision
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "另外手工课程", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1)))))
        assertEquals(EditOutcome.Stale, repository.restoreArrangement(saved.id, stale, course.id))
        assertEquals(1, repository.observeSchedules().first().single().hiddenSchoolCourses.size)
    }
    @Test fun hideAndRestoreDuringFetchAreRejectedBySharedWriteGate() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.removeArrangement(saved.id, saved.revision, id)
        saved = repository.observeSchedules().first().single()
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val check = async { repository.checkSource(plan.snapshot.scope) { started.complete(Unit); finish.await(); plan.snapshot } }
        started.await()
        try {
            assertEquals(EditOutcome.Busy, repository.restoreArrangement(saved.id, saved.revision, id))
            assertEquals(EditOutcome.Busy, repository.removeArrangement(saved.id, saved.revision, id))
            assertEquals(1, repository.observeSchedules().first().single().hiddenSchoolCourses.size)
        } finally { finish.complete(Unit); check.await() }
    }
    @Test fun failedSchoolHideRollsBackVisibilityAndRevision() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single(); val id = before.arrangements.single().id
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_hide BEFORE UPDATE ON identities BEGIN SELECT RAISE(ABORT, 'synthetic hide failure'); END")
        try { repository.removeArrangement(before.id, before.revision, id); fail("Injected hide failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertEquals(before, repository.observeSchedules().first().single())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_hide")
    }
    @Test fun failedManualDeletionRetainsBusinessAndLinkedAdjustment() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single()
        val manual = repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "未删除手工示例", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1))))) as EditOutcome.Saved
        saved = repository.observeSchedules().first().single()
        repository.saveSingleException(saved.id, saved.revision, SingleException.Cancel(manual.arrangementId, plan.term.dateOf(1, 2)))
        val before = repository.observeSchedules().first().single()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON identities BEGIN SELECT RAISE(ABORT, 'synthetic delete failure'); END")
        try { repository.removeArrangement(before.id, before.revision, manual.arrangementId); fail("Injected delete failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertEquals(before, repository.observeSchedules().first().single())
        assertNotNull(database.schedules().manual(manual.arrangementId.toString()))
        assertEquals(1, database.schedules().exceptions(manual.arrangementId.toString()).size)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_delete")
    }
    @Test fun restorationRejectsVisibleManualAndWrongTermIdentities() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val schoolId = saved.arrangements.single().id
        assertEquals(EditOutcome.Missing, repository.restoreArrangement(saved.id, saved.revision, schoolId))
        val manual = repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "不可恢复手工示例", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(1))))) as EditOutcome.Saved
        saved = repository.observeSchedules().first().single()
        assertEquals(EditOutcome.Missing, repository.restoreArrangement(saved.id, saved.revision, manual.arrangementId))
        val other = repository.createManualTerm(ManualTermPlan("2025", "1", Term(LocalDate.parse("2026-03-02"), 19), plan.periods)) as ManualTermOutcome.Saved
        assertEquals(EditOutcome.Missing, repository.removeArrangement(other.termId, 1, schoolId))
    }
    @Test fun restoringHiddenSchoolCourseKeepsOverlapWarningAndAllRows() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val schoolId = saved.arrangements.single().id
        repository.removeArrangement(saved.id, saved.revision, schoolId)
        saved = repository.observeSchedules().first().single()
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "重叠手工课程", "", "", SchedulingGroup(1, setOf(1), MeetingTime.Periods(setOf(1)))))
        saved = repository.observeSchedules().first().single()
        val result = repository.restoreArrangement(saved.id, saved.revision, schoolId) as EditOutcome.Saved
        assertEquals(1, result.overlappingArrangements)
        saved = repository.observeSchedules().first().single()
        assertEquals(2, saved.arrangements.size); assertTrue(saved.hiddenSchoolCourses.isEmpty())
    }
    @Test fun failedRestorationDoesNotUnhideOrAdvanceRevision() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.removeArrangement(saved.id, saved.revision, id)
        val before = repository.observeSchedules().first().single()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE UPDATE ON identities BEGIN SELECT RAISE(ABORT, 'synthetic restore failure'); END")
        try { repository.restoreArrangement(before.id, before.revision, id); fail("Injected restoration failure ignored") }
        catch (_: Exception) { /* expected */ }
        assertEquals(before, repository.observeSchedules().first().single())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
    }

    private fun source(vararg meetings: ParsedMeeting, unscheduled: List<String> = plan.snapshot.unscheduledNames) = plan.snapshot.copy(
        meetings = meetings.toList(), unscheduledNames = unscheduled,
        doubts = meetings.filter { it.room.isBlank() }.map { ParseDoubt("缺地点", kind = DoubtKind.MISSING_ROOM, meeting = it) } +
            if (unscheduled.isEmpty()) emptyList() else listOf(ParseDoubt("未排课", kind = DoubtKind.UNSCHEDULED)),
    )
    private suspend fun pending(snapshot: SchoolSnapshot): SavedSchedule {
        val current = repository.observeSchedules().first().single()
        val fetchedAt = maxOf(current.lastSuccessfulCheck ?: plan.fetchedAt, current.schoolReview?.fetchedAt ?: plan.fetchedAt).plusSeconds(60)
        repository.confirmImport(plan.copy(snapshot = snapshot, acknowledgedDoubts = snapshot.doubts.indices.toSet(), fetchedAt = fetchedAt))
        return repository.observeSchedules().first().single()
    }
    private fun reviewPlan(saved: SavedSchedule, links: Map<Int, java.util.UUID?>, removed: Map<java.util.UUID, RemovedSchoolChoice> = emptyMap(), choices: Map<ReviewFieldKey, ConflictChoice> = emptyMap()) =
        SchoolReviewPlan(saved.id, saved.revision, saved.schoolReview!!.id, links, removed, choices, saved.schoolReview.snapshot.doubts.indices.toSet())

    @Test fun changedCandidateSurvivesReopenAndRepeatedCheckKeepsCandidateId() = runBlocking {
        repository.confirmImport(plan)
        val before = repository.observeSchedules().first().single()
        val snapshot = source(meeting.copy(teacher = "新学校教师"))
        val first = pending(snapshot)
        assertEquals(before.copy(lastSuccessfulCheck = plan.fetchedAt.plusSeconds(60)), first.copy(schoolReview = null))
        database.close(); reopen()
        assertEquals(first, repository.observeSchedules().first().single())
        repository.confirmImport(plan.copy(snapshot = snapshot, acknowledgedDoubts = snapshot.doubts.indices.toSet(), fetchedAt = plan.fetchedAt.plusSeconds(120)))
        val second = repository.observeSchedules().first().single()
        assertEquals(first.schoolReview!!.id, second.schoolReview!!.id)
        assertEquals(plan.fetchedAt.plusSeconds(120), second.schoolReview.fetchedAt)
        assertEquals(1, database.openHelper.readableDatabase.query("SELECT * FROM school_reviews").use { it.count })
    }
    @Test fun replacedCandidateRejectsOldConfirmationAndOlderDraftCannotEraseNewCandidate() = runBlocking {
        repository.confirmImport(plan)
        val first = pending(source(meeting.copy(teacher = "候选一")))
        val selected = reviewPlan(first, mapOf(0 to first.arrangements.single().id))
        val second = pending(source(meeting.copy(teacher = "候选二")))
        assertNotEquals(first.schoolReview!!.id, second.schoolReview!!.id)
        assertEquals(ReviewOutcome.Stale, repository.confirmSchoolReview(selected))
        repository.confirmImport(plan) // old unchanged draft must not erase the newer candidate
        assertEquals(second, repository.observeSchedules().first().single())
        repository.confirmImport(plan.copy(fetchedAt = plan.fetchedAt.plusSeconds(180)))
        assertNull(repository.observeSchedules().first().single().schoolReview)
    }
    @Test fun pairedIndependentChangesKeepUuidColorManualAndExceptionAcrossReopen() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(id, meeting.name, meeting.teacher, "本地地点", SchedulingGroup(1, meeting.weeks, MeetingTime.Periods(meeting.periods)), CourseColor.MINT))
        saved = repository.observeSchedules().first().single()
        val exception = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, exception)
        saved = repository.observeSchedules().first().single()
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "手工保留", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(3)))))
        saved = pending(source(meeting.copy(teacher = "学校新教师"), unscheduled = listOf("新未排课示例")))
        assertTrue(previewSchoolReview(saved, mapOf(0 to id), emptyMap()).conflicts.isEmpty())
        assertTrue(repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to id))) is ReviewOutcome.Saved)
        val after = repository.observeSchedules().first().single()
        val course = after.arrangements.single { it.id == id }
        assertEquals("学校新教师", course.teacher); assertEquals("本地地点", course.room)
        assertEquals(CourseColor.MINT, after.colors[id]); assertEquals(listOf(exception), after.exceptions)
        assertEquals(2, after.arrangements.size); assertEquals(listOf("新未排课示例"), after.unscheduled)
        assertEquals("学校新教师", after.schoolBaselines.getValue(id).teacher)
        assertEquals("本地地点", database.schedules().overrides(id.toString())!!.room)
        assertNull(after.schoolReview)
        database.close(); reopen(); assertEquals(after, repository.observeSchedules().first().single())
    }
    @Test fun fieldConflictNeedsChoiceAndRevisionCheckProtectsLaterLocalEdit() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(id, meeting.name, "本地教师", "", SchedulingGroup(1, meeting.weeks, MeetingTime.Periods(meeting.periods))))
        saved = pending(source(meeting.copy(teacher = "学校新教师")))
        val request = reviewPlan(saved, mapOf(0 to id))
        assertEquals(listOf(ReviewField.TEACHER), previewSchoolReview(saved, request.links, emptyMap()).conflicts.map { it.key.field })
        assertEquals(ReviewOutcome.Invalid, repository.confirmSchoolReview(request))
        assertEquals(saved, repository.observeSchedules().first().single())
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(id, meeting.name, "再次修改", "", SchedulingGroup(1, meeting.weeks, MeetingTime.Periods(meeting.periods))))
        assertEquals(ReviewOutcome.Stale, repository.confirmSchoolReview(request.copy(choices = mapOf(ReviewFieldKey(0, ReviewField.TEACHER) to ConflictChoice.KEEP_LOCAL))))
        saved = repository.observeSchedules().first().single()
        assertTrue(repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to id), choices = mapOf(ReviewFieldKey(0, ReviewField.TEACHER) to ConflictChoice.KEEP_LOCAL))) is ReviewOutcome.Saved)
        val after = repository.observeSchedules().first().single()
        assertEquals("再次修改", after.arrangements.single().teacher)
        assertEquals("学校新教师", after.schoolBaselines.getValue(id).teacher)
    }
    @Test fun schedulingConflictSelectsWholeGroupAndRetainsOrphanedOriginalDate() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val custom = MeetingTime.Custom(TimeRange(java.time.LocalTime.parse("12:00"), java.time.LocalTime.parse("13:00")))
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(id, meeting.name, meeting.teacher, "", SchedulingGroup(2, setOf(1, 2), custom)))
        saved = repository.observeSchedules().first().single()
        val exception = SingleException.Cancel(id, plan.term.dateOf(1, 2))
        repository.saveSingleException(saved.id, saved.revision, exception)
        saved = pending(source(meeting.copy(weekday = 3, weeks = setOf(2, 3), periods = setOf(5))))
        assertEquals(listOf(ReviewField.SCHEDULE), previewSchoolReview(saved, mapOf(0 to id), emptyMap()).conflicts.map { it.key.field })
        val result = repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to id), choices = mapOf(ReviewFieldKey(0, ReviewField.SCHEDULE) to ConflictChoice.USE_SCHOOL))) as ReviewOutcome.Saved
        val after = repository.observeSchedules().first().single()
        assertEquals(1, result.orphanedExceptions)
        assertEquals(SchedulingGroup(3, setOf(2, 3), MeetingTime.Periods(setOf(5))), after.arrangements.single().schedulingGroup())
        assertEquals(listOf(exception), after.orphanedExceptions); assertTrue(after.exceptions.isEmpty())
        assertEquals(1, database.schedules().exceptions(id.toString()).size)
    }
    @Test fun hiddenConversionRetainsHistoryAndExceptionsAndExplicitRelinkDoesNotUnhide() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        val exception = SingleException.Cancel(id, plan.term.dateOf(1, 1))
        repository.saveSingleException(saved.id, saved.revision, exception)
        saved = repository.observeSchedules().first().single(); repository.removeArrangement(saved.id, saved.revision, id)
        val replacement = meeting.copy(name = "新课程", room = "示例地点")
        saved = pending(source(replacement))
        assertTrue(repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to null), mapOf(id to RemovedSchoolChoice.KEEP_MANUAL))) is ReviewOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertEquals(CourseOrigin.MANUAL, saved.origins[id]); assertTrue(saved.arrangements.none { it.id == id })
        assertEquals(listOf(exception), saved.hiddenSchoolCourses.single().exceptions)
        assertTrue(id in saved.schoolBaselines); assertEquals(1, database.schedules().baselines(saved.id.toString()).size)
        assertTrue(repository.confirmImport(plan.copy(snapshot = source(replacement), acknowledgedDoubts = setOf(0), fetchedAt = plan.fetchedAt.plusSeconds(120))) is ImportOutcome.Saved)
        saved = pending(source(replacement, meeting))
        val replacementId = saved.arrangements.single().id
        val newRequest = reviewPlan(saved, mapOf(0 to replacementId, 1 to null))
        assertEquals(ReviewOutcome.Invalid, repository.confirmSchoolReview(newRequest)) // history needs explicit duplicate confirmation
        assertTrue(repository.confirmSchoolReview(newRequest.copy(links = mapOf(0 to replacementId, 1 to id))) is ReviewOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertEquals(CourseOrigin.SCHOOL, saved.origins[id]); assertTrue(saved.arrangements.none { it.id == id })
        assertTrue(repository.restoreArrangement(saved.id, saved.revision, id) is EditOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertEquals(2, saved.arrangements.size); assertEquals(listOf(exception), saved.exceptions)
    }
    @Test fun convertedHiddenManualCanBeRestoredWithoutRejoiningSchool() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.removeArrangement(saved.id, saved.revision, id)
        saved = pending(source(meeting.copy(name = "新记录", room = "示例地点")))
        repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to null), mapOf(id to RemovedSchoolChoice.KEEP_MANUAL)))
        database.close(); reopen()
        saved = repository.observeSchedules().first().single()
        assertTrue(repository.restoreArrangement(saved.id, saved.revision, id) is EditOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertEquals(CourseOrigin.MANUAL, saved.origins[id]); assertTrue(saved.arrangements.any { it.id == id })
        assertNotNull(database.schedules().manual(id.toString()))
    }
    @Test fun schoolDeletionCascadesRelatedDataWithoutDeletingManualCourses() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.saveSingleException(saved.id, saved.revision, SingleException.Cancel(id, plan.term.dateOf(1, 1)))
        saved = repository.observeSchedules().first().single()
        repository.saveArrangement(saved.id, saved.revision, ArrangementEdit(null, "保留手工", "", "", SchedulingGroup(2, setOf(1), MeetingTime.Periods(setOf(3)))))
        saved = pending(source(meeting.copy(name = "新增学校", room = "示例地点")))
        assertTrue(repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to null), mapOf(id to RemovedSchoolChoice.DELETE))) is ReviewOutcome.Saved)
        saved = repository.observeSchedules().first().single()
        assertEquals(2, saved.arrangements.size); assertFalse(id in saved.origins)
        assertNull(database.schedules().identity(id.toString())); assertTrue(database.schedules().exceptions(id.toString()).isEmpty())
        assertFalse(id in saved.schoolBaselines)
    }
    @Test fun exactAssociationCannotBeReplacedByNewUuidAndMissingAcknowledgementCannotWrite() = runBlocking {
        repository.confirmImport(plan)
        val saved = pending(source(meeting, meeting.copy(name = "新增学校", room = "示例地点")))
        val id = saved.arrangements.single().id
        assertEquals(ReviewOutcome.Invalid, repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to null, 1 to null), mapOf(id to RemovedSchoolChoice.DELETE))))
        val valid = reviewPlan(saved, mapOf(0 to id, 1 to null))
        assertEquals(ReviewOutcome.Invalid, repository.confirmSchoolReview(valid.copy(acknowledgedDoubts = emptySet())))
        assertEquals(saved, repository.observeSchedules().first().single())
        assertTrue(repository.confirmSchoolReview(valid) is ReviewOutcome.Saved)
        assertTrue(repository.observeSchedules().first().single().arrangements.any { it.id == id })
    }
    @Test fun injectedReviewCommitFailureRollsBackBaselineProjectionAndCandidateTogether() = runBlocking(Dispatchers.IO) {
        repository.confirmImport(plan)
        val saved = pending(source(meeting.copy(teacher = "新教师")))
        val request = reviewPlan(saved, mapOf(0 to saved.arrangements.single().id))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_review BEFORE UPDATE ON projections BEGIN SELECT RAISE(ABORT, 'synthetic review failure'); END")
        try { repository.confirmSchoolReview(request); fail("Injected write failure ignored") } catch (_: Exception) { /* rollback */ }
        assertEquals(saved, repository.observeSchedules().first().single())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_review")
        assertTrue(repository.confirmSchoolReview(request) is ReviewOutcome.Saved)
    }
    @Test fun pendingReviewConfirmationRespectsGlobalWriteGate(): Unit = runBlocking {
        repository.confirmImport(plan)
        val saved = pending(source(meeting.copy(teacher = "新教师")))
        val request = reviewPlan(saved, mapOf(0 to saved.arrangements.single().id))
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val checking = async { repository.checkSource(plan.snapshot.scope) { started.complete(Unit); finish.await(); plan.snapshot } }
        started.await()
        assertEquals(ReviewOutcome.Busy, repository.confirmSchoolReview(request))
        assertEquals(saved, repository.observeSchedules().first().single())
        finish.complete(Unit)
        assertTrue(checking.await().outcome is ImportOutcome.Saved)
    }
    @Test fun unknownOrFailedFetchKeepsPendingCandidateAndSuccessTime() = runBlocking {
        repository.confirmImport(plan)
        val saved = pending(source(meeting.copy(teacher = "新教师")))
        val bad = saved.schoolReview!!.snapshot.copy(doubts = listOf(ParseDoubt("未知内容")))
        assertNull(repository.checkSource(plan.snapshot.scope) { bad }.outcome)
        try { repository.checkSource(plan.snapshot.scope) { throw SchoolException(SchoolFailure.NETWORK) }; fail("Failure ignored") }
        catch (error: SchoolException) { assertEquals(SchoolFailure.NETWORK, error.failure) }
        assertEquals(saved, repository.observeSchedules().first().single())
    }
    @Test fun pendingSchoolUpdateMustBeConfirmedBeforeHiddenRestoration() = runBlocking {
        repository.confirmImport(plan)
        var saved = repository.observeSchedules().first().single(); val id = saved.arrangements.single().id
        repository.removeArrangement(saved.id, saved.revision, id)
        saved = pending(source(meeting.copy(teacher = "最新教师")))
        assertEquals(EditOutcome.ExceptionReviewRequired, repository.restoreArrangement(saved.id, saved.revision, id))
        assertEquals(saved, repository.observeSchedules().first().single())
        repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to id)))
        saved = repository.observeSchedules().first().single()
        assertEquals("最新教师", saved.hiddenSchoolCourses.single().schoolBaseline.teacher)
        assertTrue(repository.restoreArrangement(saved.id, saved.revision, id) is EditOutcome.Saved)
    }
    @Test fun oldSnapshotAfterConfirmedUpdateCannotCreateReverseCandidate() = runBlocking {
        repository.confirmImport(plan)
        val saved = pending(source(meeting.copy(teacher = "新教师")))
        repository.confirmSchoolReview(reviewPlan(saved, mapOf(0 to saved.arrangements.single().id)))
        val after = repository.observeSchedules().first().single()
        assertEquals(ImportOutcome.StaleSnapshot, repository.confirmImport(plan))
        assertEquals(after, repository.observeSchedules().first().single())
    }
}
