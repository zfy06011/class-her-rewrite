package com.classher.timetable

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.classher.timetable.data.local.RoomScheduleRepository
import com.classher.timetable.data.local.ScheduleDatabase
import com.classher.timetable.data.local.MIGRATION_1_2
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
        database = Room.databaseBuilder(context, ScheduleDatabase::class.java, "import-test.db").addMigrations(MIGRATION_1_2).build()
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
        assertEquals(before, repository.observeSchedules().first().single())
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
}
