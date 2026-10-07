package com.classher.timetable

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.classher.timetable.data.local.RoomScheduleRepository
import com.classher.timetable.data.local.ScheduleDatabase
import com.classher.timetable.domain.*
import com.classher.timetable.domain.Arrangement
import com.classher.timetable.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Native Android/Compose render evidence; all displayed schedules are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w390dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PixelScreensTest {
    @get:Rule val compose = createComposeRule()
    private var database: ScheduleDatabase? = null
    private val models = ViewModelStore()
    private val now = Instant.parse("2026-10-05T01:00:00Z")
    private val term = Term(LocalDate.parse("2026-09-14"), 19)
    private fun fixture(courses: List<Arrangement>) = SavedSchedule(UUID(0, 1), "2026 学年 · 第 1 学期",
        SourceScope(accountDigest("synthetic-ui-account"), "2026", "0"), term, sdwuDefaultPeriods(), courses, emptyList(), now,
        colors = courses.mapIndexed { index, course -> course.id to CourseColor.entries[index % CourseColor.entries.size] }.toMap())
    private fun course(index: Int, day: Int, periods: Set<Int> = setOf(1, 2), name: String = "合成课程${index + 1}") =
        Arrangement(UUID(1, index.toLong()), name, "合成教师", "示例楼 101", day, setOf(4), MeetingTime.Periods(periods))
    private fun frame(theme: ThemePreference = ThemePreference.LIGHT, scale: Float = 1f, page: Int = 0, content: @Composable () -> Unit) {
        compose.setContent {
            ProbeTheme(theme) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    PaperBackground(Modifier.fillMaxSize()) {
                        Scaffold(containerColor = Color.Transparent, bottomBar = { PixelNavigation(page) {} }) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding)) { PixelBrand(); Box(Modifier.weight(1f)) { content() } }
                        }
                    }
                }
            }
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(image.width >= 320 && image.height >= 600)
        val folder = File("build/ui-previews"); folder.mkdirs()
        File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() { models.clear(); database?.close() }

    @Test fun emptyTodayAndNextLessonUseRealDatesAndHaveNoImportButtons() {
        val saved = fixture(listOf(course(0, 4)))
        frame { PixelHomeContent(saved, now) {} }
        compose.onNodeWithText("今天没有课程，好好安排自己的时间。").assertExists()
        compose.onNodeWithText("合成课程1").assertExists()
        compose.onNodeWithText("2026-10-08 · 08:30–10:00").assertExists()
        compose.onNodeWithText("学校导入").assertDoesNotExist()
        compose.onNodeWithText("新增").assertDoesNotExist()
        screenshot("home-empty-next-light")
    }
    @Test fun darkTodayKeepsLongNameAndEndedCourseReadable() {
        val saved = fixture(listOf(course(0, 1, name = "合成课程：很长的课程名称用于验证真实文字换行与截断行为"), course(1, 1, setOf(5, 6))))
        frame(ThemePreference.DARK, 1.3f) { PixelHomeContent(saved, now.plusSeconds(3 * 3600)) {} }
        compose.onNodeWithText("今日课程").assertExists()
        compose.onNodeWithText("已结束").assertExists()
        compose.onNodeWithText("学校导入").assertDoesNotExist()
        screenshot("home-courses-dark-large-text")
    }
    @Test fun weekShowsSevenDaysAndCourseTapRetainsOriginalDate() {
        val courses = (0..6).map { course(it, it + 1, if (it % 2 == 0) setOf(1, 2) else setOf(5, 6)) }
        var selected: Occurrence? = null
        frame(page = 1) { PixelWeekContent(fixture(courses), 4, {}, { selected = it }, today = LocalDate.parse("2026-10-05")) }
        compose.onNodeWithText("周日").assertExists()
        val target = compose.onNodeWithText("合成课程1").fetchSemanticsNode().boundsInRoot
        assertTrue(target.width >= 48f && target.height >= 48f)
        compose.onNodeWithText("合成课程1").performClick()
        assertEquals(courses.first().id, selected!!.arrangementId)
        assertEquals(LocalDate.parse("2026-10-05"), selected!!.originalDate)
        screenshot("week-seven-days-light")
    }
    @Test fun weekOverlapAndNonAdjacentPeriodsKeepEveryCourseSegment() {
        val courses = listOf(course(0, 1, setOf(1, 2, 5)), course(1, 1), course(2, 7))
        frame(ThemePreference.DARK, page = 1) { PixelWeekContent(fixture(courses), 4, {}, {}, today = LocalDate.parse("2026-10-05")) }
        compose.onAllNodesWithText("合成课程1").assertCountEquals(2)
        compose.onNodeWithText("合成课程2").assertExists()
        screenshot("week-overlap-dark")
    }
    @Test fun actualAppMovesActionsToMineAndShowsBuildVersion() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, ScheduleDatabase::class.java).build(); database = db
        val settings = object : AppSettings {
            override val theme = MutableStateFlow(ThemePreference.LIGHT)
            override suspend fun setTheme(theme: ThemePreference) { this.theme.value = theme }
        }
        val parser = object : SchoolParser {
            override fun parse(html: String, expected: SourceScope, weekCount: Int, periodsPerDay: Int): SchoolSnapshot = error("UI test never accesses school")
        }
        val model = TimetableViewModel(parser, RoomScheduleRepository(db, ScheduleWriteGate()), settings)
        models.put("pixel-test", model)
        val saved = fixture(emptyList())
        val state = TimetableState(loading = false, schedules = listOf(saved), selectedId = saved.id, status = "合成试用课表保存在本机")
        var schoolOpened = false
        compose.setContent { ProbeTheme(ThemePreference.LIGHT) {
            TimetableApp(state, model, { schoolOpened = true }, {}, {}, {})
        } }
        compose.onNodeWithText("学校导入").assertDoesNotExist()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("学校导入").assertIsDisplayed()
        compose.onNodeWithText("新增").assertIsDisplayed()
        compose.onNodeWithText("${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE} · 课程保存在此设备").assertExists()
        screenshot("mine-actions-version-light")
        compose.onNodeWithText("学校导入").performClick()
        compose.onNodeWithText("打开学校").performClick()
        assertTrue(schoolOpened)
        compose.onNodeWithText("返回我的").performClick()
        compose.onNodeWithText("学校导入").assertExists()
    }
}
