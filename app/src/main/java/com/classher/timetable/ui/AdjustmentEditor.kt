package com.classher.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classher.timetable.domain.*
import com.classher.timetable.domain.Arrangement
import java.time.LocalDate
import java.time.LocalTime

data class AdjustmentSession(val schedule: SavedSchedule, val course: Arrangement, val originalDate: LocalDate)

@Composable
fun AdjustmentEditor(session: AdjustmentSession, busy: Boolean, message: String, onCancel: () -> Unit, onSave: (SingleException?) -> Unit) {
    val saved = session.schedule; val course = session.course; val original = session.originalDate
    val old = (saved.exceptions + saved.orphanedExceptions).firstOrNull { it.arrangementId == course.id && it.originalDate == original }
    val moved = old as? SingleException.Move
    val initialTime = moved?.time ?: course.time
    val key = "${saved.id}:${course.id}:$original"
    var cancelled by rememberSaveable(key) { mutableStateOf(old is SingleException.Cancel) }
    var dateText by rememberSaveable(key) { mutableStateOf((moved?.date ?: original).toString()) }
    var custom by rememberSaveable(key) { mutableStateOf(initialTime is MeetingTime.Custom) }
    var periodsText by rememberSaveable(key) { mutableStateOf((initialTime as? MeetingTime.Periods)?.numbers?.sorted()?.joinToString(",") ?: saved.periods.keys.sorted().take(2).joinToString(",")) }
    var start by rememberSaveable(key) { mutableStateOf((initialTime as? MeetingTime.Custom)?.range?.start?.toString() ?: "08:30") }
    var end by rememberSaveable(key) { mutableStateOf((initialTime as? MeetingTime.Custom)?.range?.end?.toString() ?: "10:00") }
    var room by rememberSaveable(key) { mutableStateOf(moved?.room ?: course.room) }
    var pending by remember { mutableStateOf<String?>(null) }
    val associated = runCatching { validateSingleException(saved.term, course, saved.periods, SingleException.Cancel(course.id, original)) }.isSuccess
    val next = runCatching {
        val value = if (cancelled) SingleException.Cancel(course.id, original) else {
            val time = if (custom) {
                require(start.matches(Regex("[0-9]{2}:[0-9]{2}")) && end.matches(Regex("[0-9]{2}:[0-9]{2}")))
                MeetingTime.Custom(TimeRange(LocalTime.parse(start), LocalTime.parse(end)))
            } else MeetingTime.Periods(parseIndexSet(periodsText, saved.periods.size))
            SingleException.Move(course.id, original, LocalDate.parse(dateText), time, room)
        }
        validateSingleException(saved.term, course, saved.periods, value); value
    }.getOrNull()
    val conflicts = next?.let { value ->
        val replaced = saved.exceptions.filterNot { it.arrangementId == course.id && it.originalDate == original } + value
        conflictingOccurrencesFor(occurrences(saved.term, saved.arrangements, replaced, saved.periods), course.id, original).size
    } ?: 0
    BackHandler(!busy) { onCancel() }
    Scaffold(bottomBar = { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("返回") }
        Button(onClick = { if (cancelled) pending = "save" else next?.let(onSave) }, enabled = !busy && next != null, modifier = Modifier.weight(1f)) { Text(if (busy) "保存中…" else "保存本次调整") }
    } }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            item {
                Text("单次调课／停课", style = MaterialTheme.typography.headlineSmall)
                Text(course.name, style = MaterialTheme.typography.titleLarge)
                Text("原上课日期：$original")
                Text("只调整这次课程，其他教学周的常规安排保留。", style = MaterialTheme.typography.bodySmall)
                if (!associated) Text("原日期已不属于当前排课，请核对。此调整保留，可撤销后重新选择常规日期。", color = MaterialTheme.colorScheme.error)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item { Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                FilterChip(selected = !cancelled, onClick = { cancelled = false }, enabled = !busy && associated, label = { Text("本次调课") })
                FilterChip(selected = cancelled, onClick = { cancelled = true }, enabled = !busy && associated, label = { Text("本次停课") })
            } }
            if (!cancelled) {
                item {
                    OutlinedTextField(dateText, { dateText = it.take(10) }, label = { Text("调至日期 yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth(), enabled = !busy && associated)
                    Text("可选 ${saved.term.firstMonday} 至 ${saved.term.lastDate}，包含周末。", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !custom, onClick = { custom = false }, enabled = !busy && associated, label = { Text("按节次") })
                        FilterChip(selected = custom, onClick = { custom = true }, enabled = !busy && associated, label = { Text("自定义时间") })
                    }
                    if (!custom) OutlinedTextField(periodsText, { periodsText = it.take(120) }, label = { Text("节次，如 1,2,5") }, enabled = !busy && associated, modifier = Modifier.fillMaxWidth())
                    else Row(horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                        OutlinedTextField(start, { start = it.take(5) }, label = { Text("开始 HH:mm") }, enabled = !busy && associated, modifier = Modifier.weight(1f))
                        OutlinedTextField(end, { end = it.take(5) }, label = { Text("结束 HH:mm") }, enabled = !busy && associated, modifier = Modifier.weight(1f))
                    }
                }
                item { OutlinedTextField(room, { room = it.take(200) }, label = { Text("本次地点（可空）") }, enabled = !busy && associated, modifier = Modifier.fillMaxWidth()) }
                val move = next as? SingleException.Move
                if (move != null) item { Text("实际时段：${move.time.ranges(saved.periods).joinToString(" / ") { "${it.start}–${it.end}" }}") }
            }
            item {
                if (next == null && associated) Text("请检查日期、节次及时间范围。", color = MaterialTheme.colorScheme.error)
                if (conflicts > 0) Text("这次调整与 $conflicts 次课程重叠，仍可保存并保留提示。", color = MaterialTheme.colorScheme.error)
                if (old != null) OutlinedButton(onClick = { pending = "reset" }, enabled = !busy) { Text("撤销本次调整") }
            }
        }
    }
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text(if (pending == "reset") "撤销本次调整？" else "确认本次停课？") },
        text = { Text(if (pending == "reset") "撤销 $original 的单次调整，之后按当前常规课表显示已有安排，其他调整保留。" else "仅取消 $original 这次课程，其他教学周仍按常规课表显示。") },
        confirmButton = { TextButton(onClick = { val action = pending; pending = null; if (action == "reset") onSave(null) else next?.let(onSave) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("返回核对") } })
}
