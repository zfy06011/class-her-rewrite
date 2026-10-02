package com.classher.timetable.data.school

import com.classher.timetable.domain.ParseDoubt
import com.classher.timetable.domain.ParsedMeeting
import com.classher.timetable.domain.SchoolException
import com.classher.timetable.domain.SchoolFailure
import com.classher.timetable.domain.SchoolParser
import com.classher.timetable.domain.SchoolSnapshot
import com.classher.timetable.domain.SourceScope
import com.classher.timetable.domain.accountDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject

class SdwuParser @Inject constructor() : SchoolParser {
    companion object {
        private val expression = Regex("([0-9,，\\-–~\\s]+?)\\s*([单双])?\\s*\\[([0-9,，\\-–~\\s]+)]")
        private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
        private fun fail(reason: SchoolFailure): Nothing = throw SchoolException(reason)

        fun numbers(spec: String): Set<Int> {
            val result = mutableSetOf<Int>()
            for (part in spec.replace('，', ',').split(',')) {
                val token = part.trim()
                val range = Regex("(\\d+)\\s*[-–~]\\s*(\\d+)").matchEntire(token)
                if (range != null) {
                    val first = range.groupValues[1].toIntOrNull() ?: fail(SchoolFailure.INVALID_DATA)
                    val last = range.groupValues[2].toIntOrNull() ?: fail(SchoolFailure.INVALID_DATA)
                    if (first < 1 || last < first || last > 60) fail(SchoolFailure.INVALID_DATA)
                    result += first..last
                } else {
                    val value = token.toIntOrNull() ?: fail(SchoolFailure.INVALID_DATA)
                    if (value !in 1..60) fail(SchoolFailure.INVALID_DATA)
                    result += value
                }
            }
            if (result.isEmpty()) fail(SchoolFailure.INVALID_DATA)
            return result.toSortedSet()
        }

        fun weekAndPeriod(text: String): Pair<Set<Int>, Set<Int>> {
            val match = expression.matchEntire(text.trim()) ?: fail(SchoolFailure.INVALID_DATA)
            var weeks = numbers(match.groupValues[1])
            when (match.groupValues[2]) {
                "单" -> weeks = weeks.filter { it % 2 == 1 }.toSet()
                "双" -> weeks = weeks.filter { it % 2 == 0 }.toSet()
            }
            if (weeks.isEmpty()) fail(SchoolFailure.INVALID_DATA)
            return weeks to numbers(match.groupValues[3])
        }
    }

    override fun parse(
        html: String,
        expected: SourceScope,
        weekCount: Int,
        periodsPerDay: Int,
    ): SchoolSnapshot {
        require(weekCount in 1..60 && periodsPerDay in 1..48)
        if (html.length > 2_000_000) fail(SchoolFailure.INCOMPLETE)
        if (listOf("凭证已失效", "请重新登录", "登录超时").any { it in html }) {
            fail(SchoolFailure.LOGIN_REQUIRED)
        }
        if (!Regex("</html\\s*>", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            fail(SchoolFailure.INCOMPLETE)
        }
        val document = Jsoup.parse(html)
        if (document.select("input[type=password]").isNotEmpty()) fail(SchoolFailure.LOGIN_REQUIRED)
        if (document.select("table").isEmpty()) fail(SchoolFailure.NOT_TIMETABLE)
        val scope = source(document)
        if (scope != expected) fail(SchoolFailure.IDENTITY_MISMATCH)
        val grid = grid(document, periodsPerDay)
        val meetings = linkedSetOf<ParsedMeeting>()
        val doubts = linkedSetOf<ParseDoubt>()
        for ((position, cell) in grid.toSortedMap(compareBy({ it.first }, { it.second }))) {
            val (weekday, big) = position
            for (lines in blocks(cell)) {
                if (lines.isEmpty()) continue
                val anchors = lines.indices.filter { expression.matches(lines[it]) }
                if (anchors.isEmpty()) {
                    doubts += ParseDoubt("单元格有未识别的内容", weekday, big)
                    continue
                }
                val consumed = mutableSetOf<Int>()
                for (anchor in anchors) {
                    val (weeks, periods) = weekAndPeriod(lines[anchor])
                    if (weeks.any { it > weekCount } || periods.any { it > periodsPerDay }) {
                        fail(SchoolFailure.INVALID_DATA)
                    }
                    if (anchor < 2) {
                        doubts += ParseDoubt("课程名称或教师字段缺失", weekday, big)
                        consumed += anchor
                        continue
                    }
                    val name = lines[anchor - 2]
                    val teacher = lines[anchor - 1]
                    consumed += listOf(anchor - 2, anchor - 1, anchor)
                    val next = lines.getOrNull(anchor + 1)
                    val room = if (next != null && looksLikeRoom(next)) {
                        consumed += anchor + 1
                        next
                    } else ""
                    if (room.isBlank()) doubts += ParseDoubt("教室未提供，请核对", weekday, big)
                    if (periods.none { (it + 1) / 2 == big }) {
                        doubts += ParseDoubt("网格行与节次不同，请核对", weekday, big)
                    }
                    meetings += ParsedMeeting(name, teacher, room, weekday, weeks, periods)
                }
                if (lines.indices.any { it !in consumed }) {
                    doubts += ParseDoubt("存在未归属的文本，请核对", weekday, big)
                }
            }
        }
        if (meetings.isEmpty()) fail(SchoolFailure.EMPTY)
        val listedNames = courseList(document)
        val scheduledNames = meetings.map { it.name }.toSet()
        val unscheduled = listedNames.filter { it !in scheduledNames }.distinct()
        if (unscheduled.isNotEmpty()) doubts += ParseDoubt("有 ${unscheduled.size} 门课程未提供固定排课")
        val declaredCount = Regex("课程门数\\s*[:：]?\\s*(\\d+)")
            .find(document.text())?.groupValues?.get(1)?.toIntOrNull()
        if (declaredCount != null && declaredCount != (scheduledNames + listedNames).size) {
            doubts += ParseDoubt("课程总数与已识别课程不一致，请核对")
        }
        return SchoolSnapshot(scope, meetings.toList(), unscheduled, doubts.toList())
    }

    private fun source(document: Document): SourceScope {
        fun field(vararg names: String): String {
            val values = names.flatMap { name ->
                document.select("input[name=$name]").map { it.attr("value").trim() }
            }.filter { it.isNotEmpty() }.distinct()
            if (values.size != 1) fail(SchoolFailure.IDENTITY_MISMATCH)
            return values.single()
        }
        return SourceScope(accountDigest(field("xh")), field("xn"), field("xq_m", "xq"))
    }

    private fun grid(document: Document, periods: Int): Map<Pair<Int, Int>, Element> {
        val required = (1..7).flatMap { day -> (1..(periods + 1) / 2).map { day to it } }.toSet()
        // 以星期表头和几何结构为依据；单元格 ID 不代表完整性或安排身份。
        for (table in document.select("table")) {
            val rows = table.children().flatMap {
                when (it.tagName()) {
                    "tr" -> listOf(it)
                    "tbody", "thead", "tfoot" -> it.children().filter { child -> child.tagName() == "tr" }
                    else -> emptyList()
                }
            }
            if (rows.isEmpty()) continue
            val headerText = rows.first().text()
            if (!("星期一" in headerText || "周一" in headerText)) continue
            val expanded = mutableMapOf<Pair<Int, Int>, Element>()
            for ((row, element) in rows.withIndex()) {
                var column = 0
                for (cell in element.children().filter { it.tagName() in listOf("td", "th") }) {
                    while (row to column in expanded) column++
                    fun span(name: String): Int {
                        if (!cell.hasAttr(name)) return 1
                        val value = cell.attr(name).toIntOrNull() ?: fail(SchoolFailure.INCOMPLETE)
                        if (value !in 1..10) fail(SchoolFailure.INCOMPLETE)
                        return value
                    }
                    for (dr in 0 until span("rowspan")) for (dc in 0 until span("colspan")) {
                        if (row + dr >= rows.size) fail(SchoolFailure.INCOMPLETE)
                        if (expanded.put(row + dr to column + dc, cell) != null) fail(SchoolFailure.INCOMPLETE)
                    }
                    column += span("colspan")
                    if (column > 20) fail(SchoolFailure.INCOMPLETE)
                }
            }
            val columns = mutableMapOf<Int, Int>()
            for ((key, cell) in expanded.filterKeys { it.first == 0 }) {
                val label = cell.text().replace(Regex("\\s+"), "")
                val day = weekdays.indexOfFirst { label == "星期$it" || label == "周$it" } + 1
                if (day > 0 && columns.put(day, key.second) != null) fail(SchoolFailure.INCOMPLETE)
            }
            if (columns.keys != (1..7).toSet() || rows.size != (periods + 1) / 2 + 1) {
                fail(SchoolFailure.INCOMPLETE)
            }
            // 已验证形状：一个或两个时间栏 + 七个星期；不能忽略额外列或越界跨度。
            val headerWidth = expanded.keys.count { it.first == 0 }
            if (headerWidth !in 8..9 || columns.values.toSet() != (headerWidth - 7 until headerWidth).toSet()) {
                fail(SchoolFailure.INCOMPLETE)
            }
            val expectedPositions = rows.indices.flatMap { row -> (0 until headerWidth).map { row to it } }.toSet()
            if (expanded.keys != expectedPositions) fail(SchoolFailure.INCOMPLETE)
            return required.associateWith { (day, big) ->
                expanded[big to requireNotNull(columns[day])] ?: fail(SchoolFailure.INCOMPLETE)
            }
        }
        fail(SchoolFailure.NOT_TIMETABLE)
    }

    private fun blocks(cell: Element): List<List<String>> {
        val containers = cell.children().filter { it.tagName() in listOf("div", "p", "table") }
        val hasOutsideContent = cell.clone().also { it.select("div, p, table").remove() }.text().isNotBlank()
        val fragments = if (containers.isNotEmpty() && !hasOutsideContent) containers.map { it.outerHtml() }
            else listOf(cell.html())
        return fragments.map { fragment ->
            fragment.replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li|tr|td|table|span)>"), "\n")
                .split('\n').map { Jsoup.parseBodyFragment(it).text().trim() }.filter { it.isNotBlank() }
        }
    }

    private fun looksLikeRoom(text: String): Boolean = text.length <= 60 &&
        !expression.containsMatchIn(text) && Regex("\\d|楼|室|馆|场|实验|机|校区").containsMatchIn(text)

    private fun courseList(document: Document): List<String> {
        val names = mutableListOf<String>()
        for (table in document.select("table")) {
            if (!("修读性质" in table.text() || "上课班级代码" in table.text())) continue
            var nameIndex: Int? = null
            for (row in table.select("tr")) {
                val cells = row.children().filter { it.tagName() in listOf("td", "th") }
                val header = cells.indexOfFirst { it.text().trim() in listOf("课程", "课程名称", "课程名") }
                if (header >= 0) { nameIndex = header; continue }
                val index = nameIndex ?: continue
                val name = cells.getOrNull(index)?.text()?.replace(Regex("\\[.*?]"), "")?.trim()
                if (!name.isNullOrBlank()) names += name
            }
        }
        return names
    }
}
