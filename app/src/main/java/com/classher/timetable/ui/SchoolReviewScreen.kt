package com.classher.timetable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classher.timetable.domain.*
import java.util.UUID
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolReviewScreen(saved: SavedSchedule, busy: Boolean, message: String, onCancel: () -> Unit, onConfirm: (SchoolReviewPlan) -> Unit) {
    val review = requireNotNull(saved.schoolReview)
    val active = saved.origins.filterValues { it == CourseOrigin.SCHOOL }.keys
    val candidates = remember(saved) { schoolReviewCandidates(review, saved.schoolBaselines.filterKeys { it in active }) }
    val historical = remember(saved) { schoolReviewCandidates(review, saved.schoolBaselines.filterKeys { it !in active }) }
    val current = remember(saved) { (saved.arrangements + saved.hiddenSchoolCourses.map { it.arrangement }).associateBy { it.id } }
    var links by remember(saved) { mutableStateOf(candidates.filter { it.exactMatches.size == 1 }.associate { it.index to (it.exactMatches.single() as UUID?) }) }
    var removed by remember(saved) { mutableStateOf<Map<UUID, RemovedSchoolChoice>>(emptyMap()) }
    var choices by remember(saved) { mutableStateOf<Map<ReviewFieldKey, ConflictChoice>>(emptyMap()) }
    var acknowledged by remember(saved) { mutableStateOf<Set<Int>>(emptySet()) }
    var duplicates by remember(saved) { mutableStateOf<Set<Int>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    val unlinked = active - links.values.filterNotNull().toSet()
    val preview = previewSchoolReview(saved, links, emptyMap())
    val conflictKeys = preview.conflicts.map { it.key }.toSet()
    val ready = links.size == candidates.size && unlinked.all { it in removed } && conflictKeys.all { it in choices } &&
        acknowledged == review.snapshot.doubts.indices.toSet() && candidates.all {
            links[it.index] != null || historical[it.index].exactMatches.isEmpty() || it.index in duplicates
        }
    BackHandler { if (!busy) onCancel() }
    Scaffold(topBar = { TopAppBar(title = { Text("核对学校更新") }, navigationIcon = { TextButton(onClick = onCancel, enabled = !busy) { Text("返回") } }) },
        bottomBar = { Surface(tonalElevation = 2.dp) {
            Button(onClick = { confirm = true }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("确认本次更新") }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = LayoutArrangement.spacedBy(16.dp)) {
            item {
                Text("获取于 ${review.fetchedAt.atZone(SchoolZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}", style = MaterialTheme.typography.bodySmall)
                Text("完全一致的记录已关联；其余请选择旧安排或确认新增。未确认时保留原课表。", style = MaterialTheme.typography.bodyMedium)
                Text("返回或关闭应用后学校候选仍保留，尚未提交的选择需重新核对。", style = MaterialTheme.typography.bodySmall)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            items(candidates, key = { it.index }) { candidate ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    Text("${candidate.index + 1}. 学校新记录", style = MaterialTheme.typography.titleMedium)
                    CoursePreview(candidate.meeting.arrangement(UUID(0, candidate.index.toLong() + 1)), saved)
                    val fixed = candidate.exactMatches.size == 1
                    if (fixed) Text("与旧学校记录完全一致，保留课程身份。", style = MaterialTheme.typography.bodySmall)
                    else {
                        var expanded by remember { mutableStateOf(false) }
                        val id = links[candidate.index]
                        Box {
                            OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                                Text(if (!links.containsKey(candidate.index)) "选择配对或新增" else id?.let { "配对：${current.getValue(it).name}" } ?: "确认新增")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("新增一条学校安排") }, onClick = {
                                    links = links + (candidate.index to null); choices = choices.filterKeys { it.candidate != candidate.index }; expanded = false
                                })
                                saved.schoolBaselines.forEach { (oldId, baseline) ->
                                    val occupied = links.any { it.key != candidate.index && it.value == oldId }
                                    val local = current.getValue(oldId)
                                    DropdownMenuItem(enabled = !occupied, text = {
                                        Column {
                                            Text(local.name + if (oldId !in active) "（转手工历史，重新关联）" else "")
                                            Text("星期${baseline.weekday} · 周${baseline.weeks.sorted().joinToString(",")} · ${baseline.teacher} · ${baseline.room.ifBlank { "地点未提供" }}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }, onClick = { links = links + (candidate.index to oldId); choices = choices.filterKeys { it.candidate != candidate.index }; expanded = false })
                                }
                            }
                        }
                        if (links.containsKey(candidate.index) && id == null && historical[candidate.index].exactMatches.isNotEmpty()) {
                            CheckRow(candidate.index in duplicates, "存在完全一致的转手工历史。仍确认新增并保留原手工课程", !busy) { checked ->
                                duplicates = if (checked) duplicates + candidate.index else duplicates - candidate.index
                            }
                        }
                    }
                    links[candidate.index]?.let { id ->
                        Text("旧学校记录", style = MaterialTheme.typography.titleSmall); CoursePreview(saved.schoolBaselines.getValue(id), saved)
                        Text("当前本地内容${if (saved.hiddenSchoolCourses.any { it.arrangement.id == id }) "（保持隐藏）" else ""}", style = MaterialTheme.typography.titleSmall)
                        CoursePreview(current.getValue(id), saved)
                        Text("保留原身份和相关单次调整；不再关联的调整会显示待核对。", style = MaterialTheme.typography.bodySmall)
                    }
                    preview.conflicts.filter { it.key.candidate == candidate.index }.forEach { conflict ->
                        HorizontalDivider()
                        Text("${fieldLabel(conflict.key.field)}双方均有变化，请选择", style = MaterialTheme.typography.titleSmall)
                        Text("旧学校：${conflict.previous}\n本地：${conflict.local}\n新学校：${conflict.incoming}")
                        Row(horizontalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                            ConflictChoice.entries.forEach { choice -> FilterChip(selected = choices[conflict.key] == choice, enabled = !busy,
                                onClick = { choices = choices + (conflict.key to choice) }, label = { Text(if (choice == ConflictChoice.KEEP_LOCAL) "保留本地" else "采用学校") }) }
                        }
                    }
                } }
            }
            items(unlinked.toList(), key = { it.toString() }) { id ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = LayoutArrangement.spacedBy(8.dp)) {
                    Text("旧记录尚未配对：${current.getValue(id).name}", style = MaterialTheme.typography.titleMedium)
                    CoursePreview(current.getValue(id), saved)
                    Text("如学校只是修改了这条安排，请先在上方配对；确认学校移除后再选择处理。", style = MaterialTheme.typography.bodySmall)
                    FilterChip(selected = removed[id] == RemovedSchoolChoice.KEEP_MANUAL, enabled = !busy,
                        onClick = { removed = removed + (id to RemovedSchoolChoice.KEEP_MANUAL) }, label = { Text("转为手工，保留内容与调整") })
                    FilterChip(selected = removed[id] == RemovedSchoolChoice.DELETE, enabled = !busy,
                        onClick = { removed = removed + (id to RemovedSchoolChoice.DELETE) }, label = { Text("删除记录及关联调整") })
                    if (saved.hiddenSchoolCourses.any { it.arrangement.id == id }) Text("转手工后仍隐藏，可在我的中恢复。")
                } }
            }
            item {
                Text("未排课清单", style = MaterialTheme.typography.titleMedium)
                Text(review.snapshot.unscheduledNames.joinToString("、").ifBlank { "学校没有未排课课程" })
                review.snapshot.doubts.forEachIndexed { index, doubt ->
                    val label = if (doubt.kind == DoubtKind.MISSING_ROOM) "${doubt.meeting?.name}：确认地点尚未提供" else "确认未排课课程保留清单"
                    CheckRow(index in acknowledged, label, !busy) { checked -> acknowledged = if (checked) acknowledged + index else acknowledged - index }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text("应用本次学校更新？") }, text = {
        Text("新增 ${links.values.count { it == null }} 条，关联 ${links.values.count { it != null }} 条；转手工 ${unlinked.count { removed[it] == RemovedSchoolChoice.KEEP_MANUAL }} 条，删除 ${unlinked.count { removed[it] == RemovedSchoolChoice.DELETE }} 条。\n\n隐藏标记保留，删除记录的关联调整会移除；其余调整保留，失去日期关联时提示核对。")
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        confirm = false
        onConfirm(SchoolReviewPlan(saved.id, saved.revision, review.id, links, removed.filterKeys { it in unlinked }, choices.filterKeys { it in conflictKeys }, acknowledged, duplicates))
    }) { Text("确认更新") } }, dismissButton = { TextButton(onClick = { confirm = false }, enabled = !busy) { Text("继续核对") } })
}

@Composable
private fun CheckRow(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row { Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled); Text(label, Modifier.weight(1f).padding(top = 12.dp)) }
}
private fun fieldLabel(field: ReviewField) = when (field) {
    ReviewField.NAME -> "名称"; ReviewField.TEACHER -> "教师"; ReviewField.ROOM -> "地点"; ReviewField.SCHEDULE -> "星期、周次和时间（整体）"
}
