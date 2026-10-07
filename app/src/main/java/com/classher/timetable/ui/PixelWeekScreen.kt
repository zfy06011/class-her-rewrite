package com.classher.timetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classher.timetable.domain.*
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private const val MinimumCourseHeight = 60f

@Composable
internal fun WeekScreen(saved: SavedSchedule?, onCourse: (Occurrence) -> Unit) {
    var week by rememberSaveable(saved?.id?.toString()) { mutableIntStateOf(saved?.term?.weekOf(LocalDate.now(SchoolZone)) ?: 1) }
    PixelWeekContent(saved, week, onWeek = { week = it }, onCourse = onCourse)
}

@Composable
internal fun PixelWeekContent(saved: SavedSchedule?, week: Int, onWeek: (Int) -> Unit, onCourse: (Occurrence) -> Unit,
    today: LocalDate = LocalDate.now(SchoolZone)) {
    val monday = saved?.term?.dateOf(week, 1) ?: today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val all = remember(saved) { saved?.let { occurrences(it.term, it.arrangements, it.exceptions, it.periods) }.orEmpty() }
    val visible = all.filter { it.date in monday..monday.plusDays(6) }
    val ranges = (saved?.periods?.values.orEmpty() + visible.flatMap { it.ranges })
    val start = minOf(8 * 60, ranges.minOfOrNull { it.start.toSecondOfDay() / 60 } ?: 8 * 60) / 60 * 60
    val end = maxOf(21 * 60, ranges.maxOfOrNull { it.end.toSecondOfDay() / 60 } ?: 21 * 60).let { ceil(it / 60.0).toInt() * 60 }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onWeek(week - 1) }, enabled = saved != null && week > 1, modifier = Modifier.width(48.dp)) { Text("‹", fontSize = 28.sp) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = LayoutArrangement.spacedBy(12.dp)) {
                    PixelIcon(PixelGlyph.CALENDAR, Modifier.size(28.dp))
                    Text(if (saved == null) "本周课表" else "第 $week 教学周", style = MaterialTheme.typography.headlineSmall)
                }
                Text("$monday  ~  ${monday.plusDays(6)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { onWeek(week + 1) }, enabled = saved != null && week < saved.term.weekCount, modifier = Modifier.width(48.dp)) { Text("›", fontSize = 28.sp) }
        }
        if (saved == null) Text("在“我的”建立学期或导入课表", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).testTag("week-viewport")) {
            val axis = 38f
            val baseWidth = ((maxWidth.value - axis) / 7).coerceAtLeast(48f)
            // Keep the full outer frame visible; reserve room for a short lesson at day's end.
            val bodyHeight = (maxHeight.value - 44f - MinimumCourseHeight).coerceAtLeast(360f)
            val scale = bodyHeight / (end - start)
            val days = (1..7).map { day -> weekLanes(visible.filter { it.date.dayOfWeek.value == day }, scale) }
            val widths = days.map { baseWidth * it.size.coerceAtLeast(1) }
            val width = axis + widths.sum()
            val colors = LocalCourseColors.current
            Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                PixelPanel(Modifier.width(width.dp).testTag("week-grid"), shadow = true) {
                    Column {
                        Row {
                            Box(Modifier.width(axis.dp).height(44.dp), contentAlignment = Alignment.Center) { Text("时间", style = MaterialTheme.typography.labelMedium) }
                            for (day in 1..7) Surface(color = colors[(day - 1) % colors.size]) {
                                Column(Modifier.width(widths[day - 1].dp).height(44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = LayoutArrangement.Center) {
                                    Text("周${weekdays[day - 1]}", style = MaterialTheme.typography.labelLarge)
                                    Text(monday.plusDays((day - 1).toLong()).format(DateTimeFormatter.ofPattern("MM/dd")), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Box(Modifier.width(width.dp).height((bodyHeight + MinimumCourseHeight).dp)) {
                            TimeBands(start, end, scale, axis, widths, Modifier.fillMaxSize())
                            for (minute in start..end step 60) {
                                Text("${(minute / 60).toString().padStart(2, '0')}:00", Modifier.offset(x = 6.dp, y = ((minute - start) * scale + 4).dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
                            }
                            var left = axis
                            days.forEachIndexed { day, lanes ->
                                val dayWidth = widths[day]
                                lanes.forEachIndexed { laneIndex, lane -> lane.forEach { lesson ->
                                    val y = (lesson.range.start.toSecondOfDay() / 60f - start) * scale
                                    val height = ((lesson.range.end.toSecondOfDay() - lesson.range.start.toSecondOfDay()) / 60f * scale).coerceAtLeast(MinimumCourseHeight)
                                    val color = saved?.colors?.get(lesson.occurrence.arrangementId) ?: CourseColor.PINK
                                    val description = "${lesson.occurrence.name}，${lesson.occurrence.date}，${lesson.range.start}到${lesson.range.end}，${lesson.occurrence.room.ifBlank { "地点未提供" }}"
                                    Box(Modifier.offset(x = (left + laneIndex * baseWidth).dp, y = y.dp).width(baseWidth.dp).height(height.dp)
                                        .semantics { contentDescription = description }.clickable(role = Role.Button) { onCourse(lesson.occurrence) }) {
                                    PixelPanel(Modifier.fillMaxSize().padding(2.dp), colors[color.ordinal], shadow = false) {
                                        Column(Modifier.padding(3.dp), verticalArrangement = LayoutArrangement.spacedBy(2.dp)) {
                                            PixelIcon(PixelGlyph.BOOK, Modifier.size(10.dp))
                                            Text(lesson.occurrence.name, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.sp),
                                                fontWeight = FontWeight.Bold, maxLines = if (height >= 72) 3 else 2, overflow = TextOverflow.Ellipsis)
                                            Text((if (lesson.occurrence.adjusted) "调课·" else "") + lesson.occurrence.room.ifBlank { "地点待核对" }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp, lineHeight = 9.sp, letterSpacing = 0.sp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    }
                                } }
                                left += dayWidth
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep every segment and separate visual overlaps, including short custom periods. */
private fun weekLanes(courses: List<Occurrence>, scale: Float): List<List<LessonSegment>> {
    val lanes = mutableListOf<MutableList<LessonSegment>>()
    courses.flatMap { course -> course.ranges.map { LessonSegment(course, it) } }
        .sortedWith(compareBy({ it.range.start }, { it.range.end }, { it.occurrence.arrangementId.toString() })).forEach { segment ->
            val lane = lanes.firstOrNull { entries -> entries.none { previous ->
                val oldStart = previous.range.start.toSecondOfDay() / 60f
                val oldEnd = maxOf(previous.range.end.toSecondOfDay() / 60f, oldStart + MinimumCourseHeight / scale)
                val nextStart = segment.range.start.toSecondOfDay() / 60f
                val nextEnd = maxOf(segment.range.end.toSecondOfDay() / 60f, nextStart + MinimumCourseHeight / scale)
                oldStart < nextEnd && nextStart < oldEnd
            } } ?: mutableListOf<LessonSegment>().also { lanes.add(it) }
            lane.add(segment)
        }
    return lanes
}

@Composable
private fun TimeBands(start: Int, end: Int, scale: Float, axis: Float, widths: List<Float>, modifier: Modifier) {
    val palette = LocalPaperColors.current
    val lineColor = palette.highlight
    Canvas(modifier) {
        fun y(minute: Int) = (minute - start) * scale.dp.toPx()
        val morningEnd = y(12 * 60 + 30).coerceIn(0f, size.height)
        val afternoonEnd = y(18 * 60).coerceIn(morningEnd, size.height)
        drawRect(palette.morning, size = Size(size.width, morningEnd))
        drawRect(palette.afternoon, Offset(0f, morningEnd), Size(size.width, afternoonEnd - morningEnd))
        drawRect(palette.evening, Offset(0f, afternoonEnd), Size(size.width, size.height - afternoonEnd))
        val unit = 4.dp.toPx()
        val sun = Offset(size.width * .84f, morningEnd * .4f)
        drawRect(palette.sun.copy(alpha = .6f), sun, Size(unit * 9, unit * 9))
        for (step in 0..3) {
            drawRect(palette.sun.copy(alpha = .45f), sun + Offset(-unit * (step + 2), unit * step), Size(unit, unit))
            drawRect(palette.sun.copy(alpha = .45f), sun + Offset(unit * (step + 9), unit * step), Size(unit, unit))
        }
        for (row in 0..3) for (col in 0..3) {
            cloud(palette.clouds.copy(alpha = .55f), Offset(size.width * (col + .08f) / 4, morningEnd * .22f + row * morningEnd / 4), unit)
            cloud(palette.clouds.copy(alpha = .65f), Offset(size.width * (col + .1f) / 4, morningEnd + (afternoonEnd - morningEnd) * (row + .2f) / 4), unit)
        }
        val moon = Offset(size.width * .78f, afternoonEnd + unit * 5)
        // Stair-stepped crescent, drawn as squares rather than a smoothed ellipse.
        val moonRows = listOf(4..8, 2..7, 1..5, 0..3, 0..2, 0..2, 0..3, 1..5, 2..8, 4..9)
        moonRows.forEachIndexed { row, cells -> cells.forEach { col -> drawRect(palette.clouds, moon + Offset(col * unit, row * unit), Size(unit, unit)) } }
        for (index in 0..12) {
            val x = (index * 37 % 100) / 100f * size.width
            val starY = afternoonEnd + (index * 23 % 77) / 100f * (size.height - afternoonEnd)
            drawRect(palette.clouds.copy(alpha = .8f), Offset(x, starY), Size(unit / 2, unit * 1.5f))
            drawRect(palette.clouds.copy(alpha = .8f), Offset(x - unit / 2, starY + unit / 2), Size(unit * 1.5f, unit / 2))
        }
        for (index in 0..19) {
            val blockWidth = size.width / 20
            val height = (index * 7 % 5 + 2) * unit
            drawRect(palette.skyline.copy(alpha = .65f), Offset(index * blockWidth, size.height - height), Size(blockWidth, height))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.dp.toPx()))
        for (minute in start..end step 60) drawLine(lineColor.copy(alpha = .7f), Offset(0f, y(minute)), Offset(size.width, y(minute)), .6.dp.toPx(), pathEffect = dash)
        var x = axis.dp.toPx()
        for (width in widths) { drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), .6.dp.toPx(), pathEffect = dash); x += width.dp.toPx() }
        for (at in listOf(morningEnd, afternoonEnd)) drawLine(palette.ink, Offset(0f, at), Offset(size.width, at), 1.dp.toPx(), pathEffect = dash)
    }
}
private fun DrawScope.cloud(color: Color, origin: Offset, unit: Float) {
    drawRect(color, origin + Offset(unit * 2, 0f), Size(unit * 3, unit))
    drawRect(color, origin + Offset(unit, unit), Size(unit * 5, unit))
    drawRect(color, origin + Offset(0f, unit * 2), Size(unit * 7, unit))
}
