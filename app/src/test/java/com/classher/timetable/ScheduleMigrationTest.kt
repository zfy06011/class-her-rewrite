package com.classher.timetable

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.classher.timetable.data.local.*
import com.classher.timetable.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ScheduleMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val termId = "00000000-0000-0000-0000-000000000001"
    private val courseId = "00000000-0000-0000-0000-000000000002"
    private fun seedActualV1(version: Int = 1) {
        context.deleteDatabase("migration-test.db")
        val schema = javaClass.classLoader!!.getResourceAsStream("com.classher.timetable.data.local.ScheduleDatabase/$version.json")!!.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath("migration-test.db"); file.parentFile!!.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val name = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (i in 0 until indices.length()) raw.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL("INSERT INTO terms VALUES (?, 'sdwu', ?, '2026', '0', '2026-09-14', 19, 1791158400000, 1)", arrayOf(termId, accountDigest("synthetic-account")))
            raw.execSQL("INSERT INTO periods VALUES (?, 1, '08:30', '09:15')", arrayOf(termId))
            raw.execSQL("INSERT INTO identities (id, termId, origin, hidden) VALUES (?, ?, 'school', 0)", arrayOf(courseId, termId))
            raw.execSQL("INSERT INTO school_baselines (id, name, teacher, room, weekday, weeks, periods) VALUES (?, '示例课程', '学校教师', '', 1, '1,2', '1')", arrayOf(courseId))
            raw.execSQL("INSERT INTO projections (id, termId, name, teacher, room, weekday, weeks, periods) VALUES (?, ?, '示例课程', '本地教师', '', 1, '1,2', '1')", arrayOf(courseId, termId))
            raw.execSQL("INSERT INTO local_overrides (id, teacher) VALUES (?, '本地教师')", arrayOf(courseId))
            raw.execSQL("INSERT INTO single_exceptions (arrangementId, originalDate, kind) VALUES (?, '2026-09-14', 'cancel')", arrayOf(courseId))
            raw.execSQL("INSERT INTO source_acknowledgements VALUES (?, 'missing_room')", arrayOf(courseId))
            raw.execSQL("INSERT INTO unscheduled VALUES ('00000000-0000-0000-0000-000000000003', ?, '未排课示例')", arrayOf(termId))
            raw.version = version
        } finally { raw.close() }
    }
    @After fun cleanup() { context.deleteDatabase("migration-test.db") }

    @Test fun actualV1SchemaMigratesWithoutLosingBaselineOverrideExceptionOrUuid() = runBlocking(Dispatchers.IO) {
        seedActualV1()
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "migration-test.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val saved = RoomScheduleRepository(db, ScheduleWriteGate()).observeSchedules().first().single()
            assertEquals(termId, saved.id.toString()); assertEquals(courseId, saved.arrangements.single().id.toString())
            assertEquals("本地教师", saved.arrangements.single().teacher)
            assertEquals("学校教师", db.schedules().baselines(termId).single().fields.teacher)
            assertEquals("本地教师", db.schedules().overrides(courseId)!!.teacher)
            assertEquals("cancel", db.schedules().exceptions(courseId).single().kind)
            assertEquals(listOf("未排课示例"), saved.unscheduled)
            assertEquals(CourseColor.PINK, saved.colors[saved.arrangements.single().id])
            assertEquals("periods", db.schedules().baselines(termId).single().fields.timeMode)
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally { db.close() }
    }
    @Test fun missingMigrationFailsWithoutDestructiveFallback() = runBlocking(Dispatchers.IO) {
        seedActualV1()
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "migration-test.db").build()
        try {
            try { db.openHelper.writableDatabase; fail("Missing migration was ignored") }
            catch (_: IllegalStateException) { /* expected; no destructive fallback configured */ }
        } finally { db.close() }
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration-test.db"), null)
        try {
            assertEquals(1, raw.version)
            raw.rawQuery("SELECT id FROM identities", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(courseId, cursor.getString(0)) }
        } finally { raw.close() }
    }
    @Test fun legacyManualDataGetsSeparateBusinessStorageDuringMigration() = runBlocking(Dispatchers.IO) {
        seedActualV1()
        val manualId = "00000000-0000-0000-0000-000000000004"
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration-test.db"), null)
        try {
            raw.execSQL("INSERT INTO identities (id, termId, origin, hidden) VALUES (?, ?, 'manual', 0)", arrayOf(manualId, termId))
            raw.execSQL("INSERT INTO projections (id, termId, name, teacher, room, weekday, weeks, periods) VALUES (?, ?, '手工旧记录', '', '', 2, '1', '1')", arrayOf(manualId, termId))
        } finally { raw.close() }
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "migration-test.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            assertEquals("手工旧记录", db.schedules().manual(manualId)!!.fields.name)
            assertEquals(manualId, db.schedules().manual(manualId)!!.id)
            assertEquals("periods", db.schedules().manual(manualId)!!.fields.timeMode)
            assertEquals("manual", db.schedules().identity(manualId)!!.origin)
            assertTrue(db.schedules().projections(termId).any { it.id == manualId })
        } finally { db.close() }
    }
    @Test fun actualV2SchemaMigratesAndPreservesHiddenColorAndCustomManualTime() = runBlocking(Dispatchers.IO) {
        seedActualV1(version = 2)
        val manualId = "00000000-0000-0000-0000-000000000004"
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration-test.db"), null)
        try {
            raw.execSQL("UPDATE identities SET hidden = 1, colorSlot = 3 WHERE id = ?", arrayOf(courseId))
            raw.execSQL("INSERT INTO identities VALUES (?, ?, 'manual', 0, 4)", arrayOf(manualId, termId))
            raw.execSQL("INSERT INTO manual_arrangements VALUES (?, '自定义手工', '', '', 2, '1', '', 'custom', '12:00', '13:00')", arrayOf(manualId))
            raw.execSQL("INSERT INTO projections VALUES (?, ?, '自定义手工', '', '', 2, '1', '', 'custom', '12:00', '13:00')", arrayOf(manualId, termId))
        } finally { raw.close() }
        val db = Room.databaseBuilder(context, ScheduleDatabase::class.java, "migration-test.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val saved = RoomScheduleRepository(db, ScheduleWriteGate()).observeSchedules().first().single()
            assertEquals(3, db.openHelper.readableDatabase.version)
            assertEquals(courseId, saved.hiddenSchoolCourses.single().arrangement.id.toString())
            assertEquals(CourseColor.MINT, saved.hiddenSchoolCourses.single().color)
            assertEquals(1, saved.hiddenSchoolCourses.single().exceptions.size)
            assertEquals(manualId, saved.arrangements.single().id.toString())
            assertTrue(saved.arrangements.single().time is MeetingTime.Custom)
            assertNull(saved.schoolReview)
            assertNull(db.schedules().review(termId))
        } finally { db.close() }
    }
}
