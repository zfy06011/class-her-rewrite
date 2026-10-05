package com.classher.timetable

import com.classher.timetable.data.school.SdwuParser
import com.classher.timetable.domain.accountDigest
import com.classher.timetable.domain.SchoolException
import com.classher.timetable.domain.SchoolFailure
import com.classher.timetable.domain.SourceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SdwuParserTest {
    private val parser = SdwuParser()
    private val scope = SourceScope(accountDigest("synthetic-account"), "2026", "0")
    private val lesson = "<div>示例课程<br>示例教师<br>2,6-8,12-14 双 [1-2]<br>示例楼101</div>"

    private fun html(
        course: String = lesson,
        days: Int = 7,
        rows: Int = 6,
        account: String = "synthetic-account",
        semester: String = "0",
        extra: String = "",
    ): String = buildString {
        append("<html><body><input name=xh value='$account'><input name=xn value=2026><input name=xq_m value='$semester'>")
        append("<table><tr><th>节次</th>")
        listOf("一", "二", "三", "四", "五", "六", "日").take(days).forEach { append("<th>星期$it</th>") }
        append("</tr>")
        for (big in 1..rows) {
            append("<tr><td>第${big}大节</td>")
            for (day in 1..days) append("<td>${if (day == 1 && big == 1) course else "&nbsp;"}</td>")
            append("</tr>")
        }
        append("</table>$extra</body></html>")
    }

    @Test fun filtersParityAndRetainsExplicitSets() {
        val result = parser.parse(html(), scope, 19, 12)
        assertEquals(setOf(2, 6, 8, 12, 14), result.meetings.single().weeks)
        assertEquals(setOf(1, 2), result.meetings.single().periods)
        assertTrue(result.readyForConfirmation)
        assertTrue(result.sameContent(parser.parse(html(), scope, 19, 12)))
    }

    @Test fun missingRoomAndUnscheduledRemainVisible() {
        val extra = "<table><tr><th>课程名称</th><th>修读性质</th></tr><tr><td>未排课示例[A1]</td><td>选修</td></tr></table>"
        val result = parser.parse(html(lesson.replace("<br>示例楼101", ""), extra = extra), scope, 19, 12)
        assertFalse(result.readyForConfirmation)
        assertEquals(listOf("未排课示例"), result.unscheduledNames)
        assertTrue(result.doubts.any { "教室" in it.reason })
        assertEquals(result.meetings.single(), result.doubts.single { it.kind == com.classher.timetable.domain.DoubtKind.MISSING_ROOM }.meeting)
        assertEquals(1, result.doubts.count { it.kind == com.classher.timetable.domain.DoubtKind.UNSCHEDULED })
    }

    @Test fun nonEmptyUnknownCellsAreNotDropped() {
        val text = html().replace("<td>&nbsp;</td>", "<td>未知文本</td>")
        assertFalse(parser.parse(text, scope, 19, 12).readyForConfirmation)
    }

    @Test fun rejectsUnsafeResponses() {
        rejects(SchoolFailure.LOGIN_REQUIRED, "<html>凭证已失效</html>")
        rejects(SchoolFailure.LOGIN_REQUIRED, "<html><input type=password></html>")
        rejects(SchoolFailure.INCOMPLETE, html().replace("</html>", ""))
        rejects(SchoolFailure.INCOMPLETE, html(days = 6))
        rejects(SchoolFailure.INCOMPLETE, html(rows = 5))
        rejects(SchoolFailure.EMPTY, html(course = ""))
        rejects(SchoolFailure.IDENTITY_MISMATCH, html(account = "another-synthetic-account"))
        rejects(SchoolFailure.IDENTITY_MISMATCH, html(semester = "1"))
        rejects(SchoolFailure.INVALID_DATA, html(course = lesson.replace("2,6-8,12-14 双", "20")))
        rejects(SchoolFailure.INVALID_DATA, html(course = lesson.replace("[1-2]", "[0-2]")))
    }

    @Test fun mergedGridAndRepeatedLessonDeduplicate() {
        // 一门 1–4 节课占两个大节；真实格式的 rowspan 必须展开。
        val course = lesson.replace("[1-2]", "[1-4]")
        val text = html(course).replace("<td>$course</td>", "<td rowspan=2>$course</td>")
            .replace("<tr><td>第2大节</td><td>&nbsp;</td>", "<tr><td>第2大节</td>")
        val result = parser.parse(text, scope, 19, 12)
        assertEquals(1, result.meetings.size)
        assertEquals(setOf(1, 2, 3, 4), result.meetings.single().periods)
        val missing = parser.parse(text.replace("<br>示例楼101", ""), scope, 19, 12)
        assertEquals(1, missing.doubts.count { it.kind == com.classher.timetable.domain.DoubtKind.MISSING_ROOM })
    }

    @Test fun twoLeadingTimeColumnsAndNoFormAreSupported() {
        val text = html().replace("<th>节次</th>", "<th colspan=2>节次</th>")
            .replace("<td>第1大节</td>", "<td rowspan=2>上午</td><td>第1大节</td>")
            .replace("<td>第3大节</td>", "<td rowspan=2>下午</td><td>第3大节</td>")
            .replace("<td>第5大节</td>", "<td rowspan=2>晚上</td><td>第5大节</td>")
        assertTrue(parser.parse(text, scope, 19, 12).readyForConfirmation)
    }

    @Test fun extraColumnsAndOverflowingSpanAreRejected() {
        rejects(SchoolFailure.INCOMPLETE, html().replace("第1大节</td>", "第1大节</td><td>额外单元格</td>"))
        rejects(SchoolFailure.INCOMPLETE, html().replace("<td>第6大节</td>", "<td rowspan=2>第6大节</td>"))
    }

    @Test fun decorativeCellIdsDoNotReplaceGeometryValidation() {
        val text = html().replace("<td>$lesson</td>", "<td id=k11>$lesson</td>")
        assertEquals(1, parser.parse(text, scope, 19, 12).meetings.size)
        rejects(SchoolFailure.INCOMPLETE, text.replace("第1大节</td>", "第1大节</td><td>额外单元格</td>"))
    }

    @Test fun rejectsInvalidParityAndNumbers() {
        for (expr in listOf("0[1]", "1[0]", "3-1[1]", "1 双 [1]", "61[1]", "1,,2[1]")) {
            try {
                SdwuParser.weekAndPeriod(expr)
                fail("Invalid expression accepted")
            } catch (error: SchoolException) { assertEquals(SchoolFailure.INVALID_DATA, error.failure) }
        }
    }

    private fun rejects(reason: SchoolFailure, text: String) {
        try {
            parser.parse(text, scope, 19, 12)
            fail("Unsafe response accepted")
        } catch (error: SchoolException) { assertEquals(reason, error.failure) }
    }
}
