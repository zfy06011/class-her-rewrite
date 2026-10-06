package com.classher.timetable.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classher.timetable.domain.*
import com.classher.timetable.domain.Arrangement
import java.time.format.DateTimeFormatter

data class CourseManagementSession(val schedule: SavedSchedule, val course: Arrangement, val restore: Boolean, val hidden: HiddenSchoolCourse? = null)

@Composable
fun CourseManagementDialog(session: CourseManagementSession, busy: Boolean, message: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val school = session.schedule.origins[session.course.id] == CourseOrigin.SCHOOL
    val title = if (session.restore) "恢复隐藏课程？" else if (school) "隐藏学校课程？" else "删除手工课程？"
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = LayoutArrangement.spacedBy(12.dp)) {
            Text(session.course.name, style = MaterialTheme.typography.titleMedium)
            if (session.restore) {
                val hidden = requireNotNull(session.hidden)
                if (school) session.schedule.lastSuccessfulCheck?.let { Text("最近成功学校检查：${it.atZone(SchoolZone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
                Text(if (school) "学校基线" else "历史学校记录（已转手工）", style = MaterialTheme.typography.titleSmall)
                CoursePreview(hidden.schoolBaseline, session.schedule)
                Card(colors = CardDefaults.cardColors(containerColor = LocalCourseColors.current[hidden.color.ordinal], contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = LayoutArrangement.spacedBy(4.dp)) {
                        Text("恢复后的本地安排", style = MaterialTheme.typography.titleSmall)
                        CoursePreview(hidden.arrangement, session.schedule)
                    }
                }
                Text("保留 ${hidden.exceptions.size} 项单次调整和原课程身份。恢复后可能显示时间重叠提示。")
            } else if (school) Text("仅从今日和周课表隐藏这条安排，保留学校基线、本地修改及单次调整。再次获取不会自动恢复，可在“我的”中恢复。")
            else Text("删除这条手工安排及其单次调整，删除后不再保留对应内容。其他课程不受影响。")
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(if (session.restore) "确认恢复" else if (school) "确认隐藏" else "确认删除") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("取消") } })
}

@Composable
internal fun CoursePreview(course: Arrangement, saved: SavedSchedule) {
    Text(course.name, style = MaterialTheme.typography.bodyMedium)
    Text("${course.teacher.ifBlank { "教师未填写" }} · ${course.room.ifBlank { "地点未填写" }}")
    Text("星期${listOf("一", "二", "三", "四", "五", "六", "日")[course.weekday - 1]} · ${course.time.ranges(saved.periods).joinToString(" / ") { "${it.start}–${it.end}" }}")
    Text("周次：${course.weeks.sorted().joinToString("、")}")
}
