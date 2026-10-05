package com.classher.timetable

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.classher.timetable.data.local.RoomScheduleRepository
import com.classher.timetable.data.local.ScheduleDatabase
import com.classher.timetable.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
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
        database = Room.databaseBuilder(context, ScheduleDatabase::class.java, "import-test.db").build()
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
        val results = List(2) { async(Dispatchers.IO) { repository.confirmImport(plan) } }.awaitAll()
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
}
