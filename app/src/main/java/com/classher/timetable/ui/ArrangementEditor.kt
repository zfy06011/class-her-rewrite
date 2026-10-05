package com.classher.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classher.timetable.domain.*
import com.classher.timetable.domain.Arrangement
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class EditorSession(val schedule: SavedSchedule, val initial: Arrangement?)
private val colorLabels = listOf("樱粉", "天蓝", "奶黄", "薄荷", "浅紫")
private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun ArrangementEditor(session: EditorSession, busy: Boolean, message: String, onCancel: () -> Unit, onSave: (ArrangementEdit) -> Unit) {
    val initial = session.initial
    val saved = session.schedule
    val key = "${saved.id}:${initial?.id}"
    var name by rememberSaveable(key) { mutableStateOf(initial?.name.orEmpty()) }
    var teacher by rememberSaveable(key) { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by rememberSaveable(key) { mutableStateOf(initial?.room.orEmpty()) }
    var weekday by rememberSaveable(key) { mutableIntStateOf(initial?.weekday ?: 1) }
    var weeksText by rememberSaveable(key) { mutableStateOf(initial?.weeks?.sorted()?.joinToString(",") ?: "1-${saved.term.weekCount}") }
    var periodText by rememberSaveable(key) { mutableStateOf((initial?.time as? MeetingTime.Periods)?.numbers?.sorted()?.joinToString(",") ?: saved.periods.keys.sorted().take(2).joinToString(",")) }
    var custom by rememberSaveable(key) { mutableStateOf(initial?.time is MeetingTime.Custom) }
    var start by rememberSaveable(key) { mutableStateOf((initial?.time as? MeetingTime.Custom)?.range?.start?.toString() ?: "08:30") }
    var end by rememberSaveable(key) { mutableStateOf((initial?.time as? MeetingTime.Custom)?.range?.end?.toString() ?: "10:00") }
    var color by rememberSaveable(key) { mutableIntStateOf(saved.colors[initial?.id]?.ordinal ?: 0) }
    val candidate = runCatching {
        val time = if (custom) {
            require(start.matches(Regex("[0-9]{2}:[0-9]{2}")) && end.matches(Regex("[0-9]{2}:[0-9]{2}")))
            MeetingTime.Custom(TimeRange(LocalTime.parse(start), LocalTime.parse(end)))
        } else MeetingTime.Periods(parseIndexSet(periodText, saved.periods.size))
        ArrangementEdit(initial?.id, name, teacher, room, SchedulingGroup(weekday, parseIndexSet(weeksText, saved.term.weekCount), time), CourseColor.entries[color])
            .also { it.validate(saved.term, saved.periods) }
    }.getOrNull()
    val ranges = candidate?.scheduling?.time?.ranges(saved.periods).orEmpty()
    val conflicts = candidate?.let { edit -> overlappingArrangementIds(edit.arrangement(initial?.id ?: java.util.UUID(0, 0)), saved.arrangements, saved.periods).size } ?: 0
    BackHandler(!busy) { onCancel() }
    Scaffold(bottomBar = {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { candidate?.let(onSave) }, enabled = candidate != null && !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "保存中…" else "保存课程") }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            item {
                Text(if (initial == null) "新增课程" else "编辑整条安排", style = MaterialTheme.typography.headlineSmall)
                Text(saved.title, style = MaterialTheme.typography.bodySmall)
                if (initial != null && saved.origins[initial.id] == CourseOrigin.SCHOOL) Text("修改保存在本机，学校原始安排会保留。", style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item { OutlinedTextField(name, { name = it.take(200) }, label = { Text("课程名称") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), isError = name.isBlank()) }
            item { OutlinedTextField(teacher, { teacher = it.take(200) }, label = { Text("教师（可不填）") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(room, { room = it.take(200) }, label = { Text("地点（可不填）") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            item {
                Text("星期", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    for (day in 1..7) FilterChip(selected = weekday == day, onClick = { weekday = day }, enabled = !busy, label = { Text("周${weekdayLabels[day - 1]}") })
                }
            }
            item {
                OutlinedTextField(weeksText, { weeksText = it.take(240) }, label = { Text("教学周（如 1-4,6,8）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    listOf("全部", "单周", "双周").forEachIndexed { index, label -> TextButton(enabled = !busy, onClick = {
                        weeksText = (1..saved.term.weekCount).filter { index == 0 || it % 2 == (if (index == 1) 1 else 0) }.joinToString(",")
                    }) { Text(label) } }
                }
            }
            item {
                Text("上课时间", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !custom, onClick = { custom = false }, enabled = !busy, label = { Text("按节次") })
                    FilterChip(selected = custom, onClick = { custom = true }, enabled = !busy, label = { Text("自定义时间") })
                }
                if (!custom) OutlinedTextField(periodText, { periodText = it.take(120) }, label = { Text("节次（如 1,2,5）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                else Row(horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                    OutlinedTextField(start, { start = it.take(5) }, label = { Text("开始 HH:mm") }, enabled = !busy, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it.take(5) }, label = { Text("结束 HH:mm") }, enabled = !busy, modifier = Modifier.weight(1f))
                }
                if (ranges.isNotEmpty()) Text("实际时段：${ranges.joinToString(" / ") { "${it.start}–${it.end}" }}", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("课程颜色", style = MaterialTheme.typography.titleMedium)
                val colors = LocalCourseColors.current
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    CourseColor.entries.forEach { slot -> FilterChip(selected = color == slot.ordinal, onClick = { color = slot.ordinal }, enabled = !busy,
                        label = { Text(colorLabels[slot.ordinal]) }, leadingIcon = { if (color == slot.ordinal) Text("✓") },
                        colors = FilterChipDefaults.filterChipColors(containerColor = colors[slot.ordinal], selectedContainerColor = colors[slot.ordinal])) }
                }
            }
            item {
                if (candidate == null) Text("请检查课程名、周次和时间，不能保存空或越界的安排。", color = MaterialTheme.colorScheme.error)
                if (conflicts > 0) Text("与 $conflicts 条安排时间重叠，仍可保存；课表会保留重叠提示。", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ManualTermEditor(busy: Boolean, message: String, onCancel: () -> Unit, onSave: (ManualTermPlan) -> Unit) {
    var year by rememberSaveable { mutableStateOf(LocalDate.now(SchoolZone).year.toString()) }
    var semester by rememberSaveable { mutableIntStateOf(0) }
    var dateText by rememberSaveable { mutableStateOf("") }
    var weeks by rememberSaveable { mutableStateOf("19") }
    var periodCount by rememberSaveable { mutableStateOf("12") }
    var starts by rememberSaveable { mutableStateOf(sdwuDefaultPeriods().mapValues { it.value.start.toString() }) }
    var ends by rememberSaveable { mutableStateOf(sdwuDefaultPeriods().mapValues { it.value.end.toString() }) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val plan = runCatching {
        val count = periodCount.toInt(); require(count in 1..48)
        ManualTermPlan(year, semester.toString(), Term(requireNotNull(date), weeks.toInt()), (1..count).associateWith { number ->
            require(starts[number]?.matches(Regex("[0-9]{2}:[0-9]{2}")) == true && ends[number]?.matches(Regex("[0-9]{2}:[0-9]{2}")) == true)
            TimeRange(LocalTime.parse(starts[number]), LocalTime.parse(ends[number]))
        }).also { it.validate() }
    }.getOrNull()
    BackHandler(!busy) { onCancel() }
    Scaffold(bottomBar = { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
        Button(onClick = { plan?.let(onSave) }, enabled = !busy && plan != null && confirmed, modifier = Modifier.weight(1f)) { Text("建立学期") }
    } }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            item {
                Text("先建立一个学期", style = MaterialTheme.typography.headlineSmall)
                Text("无需学校登录。先确认学期和作息，再添加课程。", style = MaterialTheme.typography.bodyMedium)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item {
                OutlinedTextField(year, { year = it.take(4); confirmed = false }, label = { Text("学年开始年份") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    for (number in 0..1) FilterChip(selected = semester == number, onClick = { semester = number; confirmed = false }, enabled = !busy, label = { Text("第 ${number + 1} 学期") })
                }
            }
            item {
                OutlinedTextField(dateText, { dateText = it.take(10); confirmed = false }, label = { Text("第一教学周周一 yyyy-MM-dd") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (date != null && date.dayOfWeek != DayOfWeek.MONDAY) TextButton(onClick = {
                    dateText = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(); confirmed = false
                }, enabled = !busy) { Text("采用该周周一 ${date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}") }
                OutlinedTextField(weeks, { weeks = it.take(2); confirmed = false }, label = { Text("总教学周数") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
            item { Text("作息表", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(periodCount, { periodCount = it.take(2); confirmed = false }, label = { Text("每日节次数量") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            items((1..(periodCount.toIntOrNull()?.coerceIn(1, 48) ?: 12)).toList()) { number -> Row(horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                OutlinedTextField(starts[number].orEmpty(), { starts = starts + (number to it.take(5)); confirmed = false }, label = { Text("$number 节开始") }, enabled = !busy, modifier = Modifier.weight(1f))
                OutlinedTextField(ends[number].orEmpty(), { ends = ends + (number to it.take(5)); confirmed = false }, label = { Text("结束") }, enabled = !busy, modifier = Modifier.weight(1f))
            } }
            item { Row {
                Checkbox(confirmed, { confirmed = it }, enabled = !busy)
                Text("已确认起点、周数和作息", Modifier.padding(top = 12.dp))
            } }
            if (plan == null) item { Text("请填写有效学年、周一日期和不重叠的作息。", color = MaterialTheme.colorScheme.error) }
        }
    }
}
