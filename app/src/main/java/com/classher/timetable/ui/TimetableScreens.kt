package com.classher.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classher.timetable.BuildConfig
import com.classher.timetable.domain.*
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun times(ranges: List<TimeRange>) = ranges.joinToString(" / ") { "${it.start.format(clockFormat)}–${it.end.format(clockFormat)}" }

@Composable
fun TimetableApp(state: TimetableState, model: TimetableViewModel, openSchool: () -> Unit, fetch: () -> Unit, checkUpdates: () -> Unit, logout: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<UUID?>(null) }
    var detailOriginalDate by remember { mutableStateOf<LocalDate?>(null) }
    state.reviewSession?.let { saved ->
        SchoolReviewScreen(saved, state.saving, state.editMessage, model::endEditing, model::confirmSchoolReview)
        return
    }
    LaunchedEffect(state.importedId) {
        if (state.importedId != null) { importing = false; page = 0; model.consumeImported() }
    }
    LaunchedEffect(state.editedId) { if (state.editedId != null) { detailId = null; model.consumeEdited() } }
    LaunchedEffect(state.createdTermId, state.selected?.id, state.saving) {
        if (state.createdTermId != null && state.createdTermId == state.selected?.id && !state.saving) model.openEditor(null)
    }
    val adjustment = state.adjustment
    if (adjustment != null) {
        AdjustmentEditor(adjustment, state.saving, state.editMessage, onCancel = model::endEditing,
            onSave = { model.saveAdjustment(adjustment, it) })
        return
    }
    val editor = state.editor
    if (editor != null) {
        ArrangementEditor(editor, state.saving, state.editMessage, onCancel = model::endEditing,
            onSave = { model.saveEdit(editor.schedule, it) })
        return
    }
    if (state.creatingTerm) {
        ManualTermEditor(state.saving, state.editMessage, onCancel = model::endEditing, onSave = model::createManualTerm)
        return
    }
    BackHandler(importing) { if (!state.saving) importing = false }
    PaperBackground(Modifier.fillMaxSize()) {
        Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = { if (!importing) PixelNavigation(page) { page = it } }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (importing) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("确认导入", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { importing = false }, enabled = !state.saving) { Text("返回我的") }
                } else PixelBrand()
                if (!importing && page != 2 && state.selected?.schoolReview != null) {
                    Text("学校更新待核对，请到“我的”处理。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (!importing && page != 2 && state.status.contains("请登录学校")) {
                    Text("需重新登录学校，可继续查看课表。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                when {
                    state.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取本地课表…", Modifier.padding(16.dp)) }
                    state.loadFailed -> {
                        Text(state.status, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        Button(onClick = model::load, modifier = Modifier.padding(16.dp)) { Text("重试读取") }
                    }
                    importing -> ImportScreen(state, model, openSchool, fetch, logout)
                    else -> {
                        if (state.schedules.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                            state.schedules.forEach { term -> FilterChip(selected = state.selected?.id == term.id, onClick = { model.selectTerm(term.id) },
                                enabled = !state.busy, label = { Text(term.title) }) }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (page) {
                                0 -> TodayScreen(state.selected) { course -> detailId = course.arrangementId; detailOriginalDate = course.originalDate }
                                1 -> WeekScreen(state.selected) { course -> detailId = course.arrangementId; detailOriginalDate = course.originalDate }
                                2 -> SettingsScreen(state, model, { importing = true }, checkUpdates, logout)
                            }
                        }
                    }
                }
            }
        }
    }
    state.management?.let { session -> CourseManagementDialog(session, state.saving, state.editMessage,
        onCancel = model::endEditing, onConfirm = { model.confirmManagement(session) }) }
    val selected = state.selected
    val detail = selected?.arrangements?.firstOrNull { it.id == detailId }
    val selectedOccurrence = remember(selected, detailId, detailOriginalDate) {
        if (detailId == null) null else selected?.let { saved -> occurrences(saved.term, saved.arrangements, saved.exceptions, saved.periods)
            .firstOrNull { it.arrangementId == detailId && it.originalDate == detailOriginalDate } }
    }
    if (detail != null) AlertDialog(onDismissRequest = { detailId = null }, title = { Text(detail.name) }, text = {
        Column(verticalArrangement = LayoutArrangement.spacedBy(8.dp)) {
            Text(if (selected.origins[detail.id] == CourseOrigin.MANUAL) "手工课程" else "学校课程")
            Text("教师：${detail.teacher.ifBlank { "未填写" }}")
            Text("常规地点：${detail.room.ifBlank { "未填写" }}")
            Text("常规：星期${dayNames[detail.weekday - 1]} · ${times(detail.time.ranges(selected.periods))}")
            selectedOccurrence?.let { occurrence ->
                Text("本次：${occurrence.date} · ${times(occurrence.ranges)}")
                Text("本次地点：${occurrence.room.ifBlank { "未填写" }}")
                if (occurrence.adjusted) Text("已调课 · 原日期 ${occurrence.originalDate}")
            }
            Text("周次：${detail.weeks.sorted().joinToString("、")}")
            val time = detail.time
            if (time is MeetingTime.Periods) Text("节次：${time.numbers.sorted().joinToString("、")}")
            OutlinedButton(onClick = { detailId = null; model.openRemoval(detail.id) }, enabled = !state.busy) {
                Text(if (selected.origins[detail.id] == CourseOrigin.SCHOOL) "隐藏学校安排" else "删除手工安排")
            }
            val original = detailOriginalDate
            if (original != null) OutlinedButton(onClick = { detailId = null; model.openAdjustment(detail.id, original) }, enabled = !state.busy) { Text("调整 $original 这一次") }
        }
    }, confirmButton = { TextButton(onClick = { detailId = null; model.openEditor(detail.id) }, enabled = !state.busy) { Text("编辑整条安排") } },
        dismissButton = { TextButton(onClick = { detailId = null }) { Text("完成") } })
}

@Composable
private fun TodayScreen(saved: SavedSchedule?, onCourse: (Occurrence) -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(saved?.id) { while (true) { now = Instant.now(); delay(30_000) } }
    PixelHomeContent(saved, now, onCourse)
}

@Composable
internal fun PixelHomeContent(saved: SavedSchedule?, now: Instant, onCourse: (Occurrence) -> Unit) {
    val all = remember(saved) { saved?.let { occurrences(it.term, it.arrangements, it.exceptions, it.periods) }.orEmpty() }
    val summary = saved?.let { summarizeToday(it.term, all, now) }
    val date = now.atZone(SchoolZone).toLocalDate()
    val semesterTitle = saved?.let { "${it.academicYear} 学年 · 第 ${it.semester.toInt() + 1} 学期" }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = LayoutArrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                PixelIcon(PixelGlyph.CALENDAR, Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · 星期${dayNames[date.dayOfWeek.value - 1]}", style = MaterialTheme.typography.headlineLarge)
                    PixelRule(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(summary?.teachingWeek?.let { "第 $it 教学周 · $semesterTitle" }
                        ?: if (saved == null) "还没有建立学期" else "当前日期不在此学期", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { NextLessonPanel(summary) }
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                PixelIcon(PixelGlyph.LIST, Modifier.size(28.dp)); Text("今日课程", style = MaterialTheme.typography.headlineSmall)
                PixelRule(Modifier.weight(1f))
            }
        }
        if (summary?.entries.isNullOrEmpty()) item { EmptyToday(saved != null) }
        else items(requireNotNull(summary).entries, key = { "${it.occurrence.arrangementId}:${it.occurrence.originalDate}" }) { entry ->
            CourseCard(entry.occurrence, entry.ended, entry.conflicting, saved?.colors?.get(entry.occurrence.arrangementId) ?: CourseColor.PINK) { onCourse(entry.occurrence) }
        }
    }
}

@Composable
private fun NextLessonPanel(summary: TodaySummary?) {
    val running = summary?.running?.firstOrNull(); val next = summary?.next
    PixelPanel(Modifier.fillMaxWidth(), LocalCourseColors.current[1]) {
        Column {
            Row(Modifier.fillMaxWidth().background(LocalCourseColors.current[1].copy(alpha = .8f)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("▶  ${if (running != null) "正在上课" else "下一节课"}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                listOf(2, 0, 3).forEach { index -> Box(Modifier.padding(start = 6.dp).size(10.dp).background(LocalCourseColors.current[index])
                    .border(1.dp, LocalPaperColors.current.ink)) }
            }
            Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(16.dp)) {
                PixelBookStack(Modifier.size(56.dp))
                PixelVerticalRule(Modifier.height(80.dp))
                Column(Modifier.weight(1f), verticalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    Text(running?.occurrence?.name ?: next?.segment?.occurrence?.name ?: if (summary == null) "新学期，从一张课表开始" else "本学期没有后续课程",
                        style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PixelRule(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.onSurfaceVariant)
                    val lesson = running ?: next?.segment
                    if (lesson != null) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                            PixelIcon(PixelGlyph.CLOCK, Modifier.size(18.dp)); Text("${lesson.occurrence.date} · ${times(listOf(lesson.range))}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (running == null && next != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                            PixelIcon(PixelGlyph.HOURGLASS, Modifier.size(18.dp))
                            val minutes = next.untilStart.toMinutes().coerceAtLeast(1)
                            Text(if (minutes < 60) "$minutes 分钟后" else "${minutes / 60} 小时 ${minutes % 60} 分钟后", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else Text(if (summary == null) "到“我的”录课或导入学校课表" else "留一点空白，按自己的节奏。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: Occurrence, ended: Boolean, conflict: Boolean, color: CourseColor, click: () -> Unit) {
    PixelPanel(onClick = click, modifier = Modifier.fillMaxWidth().alpha(if (ended) 0.65f else 1f), color = LocalCourseColors.current[color.ordinal]) {
        Column(Modifier.padding(16.dp), verticalArrangement = LayoutArrangement.spacedBy(4.dp)) {
            Text(times(course.ranges), style = MaterialTheme.typography.labelLarge)
            Text(course.name, style = MaterialTheme.typography.titleMedium)
            Text(course.room.ifBlank { "地点未填写" }, style = MaterialTheme.typography.bodyMedium)
            if (course.adjusted) Text("已调课 · 原日期 ${course.originalDate}", style = MaterialTheme.typography.labelSmall)
            if (ended) Text("已结束", style = MaterialTheme.typography.labelSmall)
            if (conflict) Text("时间重叠", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun AppVersionFooter() {
    var licenseOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Text("${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE} · 课程保存在此设备", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { licenseOpen = true }) { Text("Fusion Pixel 字体 · 开源许可", style = MaterialTheme.typography.labelSmall) }
    if (licenseOpen) AlertDialog(onDismissRequest = { licenseOpen = false }, title = { Text("字体许可") }, text = {
        val license = remember { context.assets.open("licenses/FusionPixel-LICENSES.txt").bufferedReader().use { it.readText() } }
        Text(license, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(onClick = { licenseOpen = false }) { Text("完成") } })
}

@Composable
private fun SettingsScreen(state: TimetableState, model: TimetableViewModel, onImport: () -> Unit, fetch: () -> Unit, logout: () -> Unit) {
    var showCourses by rememberSaveable(state.selected?.id?.toString()) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(16.dp)) {
        item {
            Text("我的课表", style = MaterialTheme.typography.titleLarge)
            Text(state.selected?.title ?: "还没有导入课表", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.status, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                PixelAction("新增", PixelGlyph.PLUS, LocalCourseColors.current[3], !state.busy,
                    { if (state.selected == null) model.openManualTerm() else model.openEditor(null) }, Modifier.weight(1f))
                PixelAction("学校导入", PixelGlyph.SCHOOL, LocalCourseColors.current[0], !state.busy, onImport, Modifier.weight(1f))
            }
            state.selected?.lastSuccessfulCheck?.let { Text("上次成功检查：${it.atZone(SchoolZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = fetch, enabled = !state.busy && state.selected?.scope != null, modifier = Modifier.weight(1f)) { Text("立即检查更新") }
                OutlinedButton(onClick = model::openManualTerm, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("新建手工学期") }
            }
            TextButton(onClick = logout, enabled = !state.busy) { Text("退出学校会话，保留课表") }
        }
        item {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                ThemePreference.entries.forEach { mode -> FilterChip(selected = state.theme == mode, onClick = { model.setTheme(mode) },
                    label = { Text(when (mode) { ThemePreference.SYSTEM -> "跟随系统"; ThemePreference.LIGHT -> "浅色"; ThemePreference.DARK -> "深色" }) }) }
            }
        }
        val unscheduled = state.selected?.unscheduled.orEmpty()
        if (unscheduled.isNotEmpty()) {
            item { Text("学期清单 · 未排课 ${unscheduled.size} 门", style = MaterialTheme.typography.titleMedium); Text("学校未提供固定时间，保留清单并等待安排。", style = MaterialTheme.typography.bodySmall) }
            items(unscheduled) { name -> Card(Modifier.fillMaxWidth()) { Text(name, Modifier.padding(16.dp)) } }
        }
        val saved = state.selected
        if (saved?.schoolReview != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("学校更新待核对", style = MaterialTheme.typography.titleMedium)
                    Text("完整变化已保存在本机，确认前继续使用原课表。")
                    Button(onClick = model::openSchoolReview, enabled = !state.busy) { Text("核对学校更新") }
                }
            }
        }
        val adjustments = saved?.let { it.exceptions + it.orphanedExceptions }.orEmpty().sortedBy { it.originalDate }
        if (saved != null && adjustments.isNotEmpty()) {
            item { Text("单次调整 · ${adjustments.size}", style = MaterialTheme.typography.titleMedium) }
            items(adjustments, key = { "${it.arrangementId}:${it.originalDate}" }) { adjustment ->
                Card(onClick = { model.openAdjustment(adjustment.arrangementId, adjustment.originalDate) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(saved.arrangements.firstOrNull { it.id == adjustment.arrangementId }?.name ?: "课程待核对", style = MaterialTheme.typography.titleMedium)
                        Text("原日期：${adjustment.originalDate}")
                        Text(when (adjustment) { is SingleException.Cancel -> "本次停课"; is SingleException.Move -> "调至 ${adjustment.date} · ${runCatching { times(adjustment.time.ranges(saved.periods)) }.getOrDefault("时间待核对")}" })
                        if (adjustment in saved.orphanedExceptions) Text("关联待核对，原调整仍保留", color = MaterialTheme.colorScheme.error)
                        Text("点此编辑或撤销", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (saved != null && saved.hiddenSchoolCourses.isNotEmpty()) {
            item { Text("已隐藏安排 · ${saved.hiddenSchoolCourses.size}", style = MaterialTheme.typography.titleMedium) }
            items(saved.hiddenSchoolCourses, key = { it.arrangement.id.toString() }) { hidden ->
                Card(onClick = { model.openRestoration(hidden.arrangement.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(hidden.arrangement.name, style = MaterialTheme.typography.titleMedium)
                        Text("保留本地修改及 ${hidden.exceptions.size} 项单次调整")
                        Text("点此核对并恢复", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (saved != null && saved.arrangements.isNotEmpty()) {
            item { TextButton(onClick = { showCourses = !showCourses }) { Text(if (showCourses) "收起全部安排" else "查看全部安排（${saved.arrangements.size}）") } }
            if (showCourses) items(saved.arrangements, key = { it.id.toString() }) { course ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium)
                    Text("星期${dayNames[course.weekday - 1]} · ${times(course.time.ranges(saved.periods))}")
                    Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.openEditor(course.id) }, enabled = !state.busy) { Text("编辑整条") }
                        TextButton(onClick = { model.openRemoval(course.id) }, enabled = !state.busy) { Text(if (saved.origins[course.id] == CourseOrigin.SCHOOL) "隐藏" else "删除") }
                    }
                } }
            }
        }
        item { AppVersionFooter() }
    }
}

@Composable
private fun ImportScreen(state: TimetableState, model: TimetableViewModel, openSchool: () -> Unit, fetch: () -> Unit, logout: () -> Unit) {
    var weeks by rememberSaveable { mutableStateOf(state.weekCount.toString()) }
    var periodCount by rememberSaveable { mutableStateOf(state.periodsPerDay.toString()) }
    val draft = state.draft
    val localTerm = draft?.let { data -> state.schedules.firstOrNull { it.scope == null && it.academicYear == data.snapshot.scope.year && it.semester == data.snapshot.scope.semester } }
    var linkConfirmed by remember(draft?.id, localTerm?.id) { mutableStateOf(false) }
    var dateText by remember(draft?.id) { mutableStateOf(if (draft?.snapshot?.scope?.let { it.year == "2026" && it.semester == "0" } == true) "2026-09-14" else "") }
    var starts by remember(draft?.id) { mutableStateOf(sdwuDefaultPeriods().mapValues { it.value.start.format(clockFormat) }) }
    var ends by remember(draft?.id) { mutableStateOf(sdwuDefaultPeriods().mapValues { it.value.end.format(clockFormat) }) }
    var acknowledged by remember(draft?.id) { mutableStateOf(emptySet<Int>()) }
    var confirmed by remember(draft?.id) { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val parsedPeriods = runCatching {
        (1..periodCount.toInt()).associateWith { number ->
            require(starts[number]?.matches(Regex("[0-9]{2}:[0-9]{2}")) == true && ends[number]?.matches(Regex("[0-9]{2}:[0-9]{2}")) == true)
            TimeRange(LocalTime.parse(starts[number]), LocalTime.parse(ends[number]))
        }
    }.getOrNull()
    val term = runCatching { Term(requireNotNull(date), weeks.toInt()) }.getOrNull()
    val planValid = draft != null && term != null && parsedPeriods != null && runCatching {
        ImportPlan(draft.snapshot, term, parsedPeriods, acknowledged, draft.fetchedAt).validate()
    }.isSuccess
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
        item {
            Text(state.status)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.configure(weeks.toInt(), periodCount.toInt()); openSchool() },
                    enabled = !state.busy && weeks.toIntOrNull()?.let { it in 1..60 } == true && periodCount.toIntOrNull()?.let { it in 1..48 } == true) { Text("打开学校") }
                Button(onClick = { model.configure(weeks.toInt(), periodCount.toInt()); fetch() }, enabled = !state.busy && weeks.toIntOrNull()?.let { it in 1..60 } == true && periodCount.toIntOrNull()?.let { it in 1..48 } == true) { Text("立即获取") }
            }
            if (state.running) TextButton(onClick = model::cancelFetch) { Text("取消获取") }
        }
        item {
            Text("解析范围与学校所选学期一致", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                OutlinedTextField(weeks, { weeks = it.take(2); confirmed = false }, label = { Text("总周数") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
                OutlinedTextField(periodCount, { periodCount = it.take(2); confirmed = false }, label = { Text("每日节次") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !state.busy)
            }
        }
        if (draft == null) item { Text("本人登录 → 教学安排 → 个人课表，选择学期后获取。预览未保存时关闭应用会清除；正式保存后可离线查看。") }
        else {
            val snapshot = draft.snapshot
            item {
                Text("${snapshot.scope.year} 学年 · 第 ${snapshot.scope.semester.toInt() + 1} 学期", style = MaterialTheme.typography.titleLarge)
                Text("${snapshot.meetings.size} 条安排 · ${snapshot.unscheduledNames.size} 门未排课")
                if (localTerm != null) {
                    Text("同学期已有 ${localTerm.arrangements.size} 条手工安排。接入后全部保留；起点、周数和作息必须一致。")
                    Row {
                        Checkbox(linkConfirmed, { linkConfirmed = it }, enabled = !state.busy)
                        Text("保留手工课程并接入学校", Modifier.padding(top = 12.dp))
                    }
                }
                state.repeatedIdentically?.let { Text(if (it) "两次来源与内容一致" else "本次获取内容有变化") }
                OutlinedTextField(dateText, { dateText = it.take(10); confirmed = false }, label = { Text("第一教学周周一（yyyy-MM-dd）") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                    isError = dateText.isNotBlank() && date?.dayOfWeek != DayOfWeek.MONDAY)
                if (date != null && date.dayOfWeek != DayOfWeek.MONDAY) TextButton(onClick = {
                    dateText = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(); confirmed = false
                }, enabled = !state.busy) { Text("确认采用该周周一 ${date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}") }
                Text("核对作息后保存；其他学期需自行确认日期与时间。", style = MaterialTheme.typography.bodySmall)
            }
            items((1..(periodCount.toIntOrNull()?.coerceIn(1, 48) ?: 12)).toList()) { number ->
                Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    OutlinedTextField(starts[number].orEmpty(), { starts = starts + (number to it.take(5)); confirmed = false }, label = { Text("$number 节开始") }, modifier = Modifier.weight(1f), enabled = !state.busy)
                    OutlinedTextField(ends[number].orEmpty(), { ends = ends + (number to it.take(5)); confirmed = false }, label = { Text("结束") }, modifier = Modifier.weight(1f), enabled = !state.busy)
                }
            }
            item { Row {
                Checkbox(confirmed, { confirmed = it }, enabled = !state.busy)
                Text("已确认学期起点、总周数和作息", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            } }
            itemsIndexed(snapshot.doubts) { index, doubt ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(doubt.reason, color = MaterialTheme.colorScheme.error)
                    doubt.meeting?.let { Text("${it.name} · 星期${dayNames[it.weekday - 1]} · ${it.periods.sorted().joinToString("、")} 节") }
                    if (doubt.kind == DoubtKind.OTHER) Text("此项需要修正源数据后重新获取，暂不能导入。")
                    else Row {
                        Checkbox(index in acknowledged, { checked -> acknowledged = if (checked) acknowledged + index else acknowledged - index }, enabled = !state.busy)
                        Text(if (doubt.kind == DoubtKind.MISSING_ROOM) "确认地点尚未提供" else "保留以下未排课课程清单", Modifier.padding(top = 12.dp))
                    }
                    if (doubt.kind == DoubtKind.UNSCHEDULED) snapshot.unscheduledNames.forEach { Text(it) }
                } }
            }
            item {
                Button(onClick = { model.save(draft.id, requireNotNull(term), requireNotNull(parsedPeriods), acknowledged, localTerm?.id) }, enabled = !state.busy && confirmed && planValid && (localTerm == null || linkConfirmed),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.saving) "正在保存…" else "确认并保存到本机") }
                if (!planValid) Text("保存前请完成核对，检查日期、时间和周次范围。", style = MaterialTheme.typography.bodySmall)
                Text("完整安排预览", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            }
            itemsIndexed(snapshot.meetings) { index, meeting ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${index + 1}. ${meeting.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${meeting.teacher} · ${meeting.room.ifBlank { "地点未提供" }}")
                        Text("星期${dayNames[meeting.weekday - 1]} · ${meeting.periods.sorted().joinToString("、")} 节")
                        Text("周次：${meeting.weeks.sorted().joinToString("、")}")
                    }
                }
            }
        }
        item {
            TextButton(onClick = model::clearPreview, enabled = !state.busy) { Text("清除本次预览") }
            OutlinedButton(onClick = logout, enabled = !state.busy) { Text("退出学校会话，保留本地课表") }
        }
    }
}
