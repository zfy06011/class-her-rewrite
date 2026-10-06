package com.classher.timetable

import android.app.Application
import com.classher.timetable.data.local.SchoolSnapshotCodec
import com.classher.timetable.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SchoolSnapshotCodecTest {
    private val meeting = ParsedMeeting("合成课程\"\n示例", "合成教师", "", 1, setOf(1, 3), setOf(1, 5))
    private val snapshot = SchoolSnapshot(SourceScope(accountDigest("synthetic-account"), "2026", "0"), listOf(meeting), listOf("未排课示例"),
        listOf(ParseDoubt("地点缺失", 1, 1, DoubtKind.MISSING_ROOM, meeting), ParseDoubt("未排课", kind = DoubtKind.UNSCHEDULED)))
    @Test fun businessFieldsAndDoubtAssociationRoundTripWithoutAccountOrSessionPayload() {
        val encoded = SchoolSnapshotCodec.encode(snapshot)
        assertEquals(snapshot, SchoolSnapshotCodec.decode(encoded))
        assertFalse(encoded.contains("synthetic-account"))
        assertEquals(setOf("version", "digest", "year", "semester", "meetings", "unscheduled", "doubts"), JSONObject(encoded).keys().asSequence().toSet())
    }
    @Test fun unknownVersionAndBrokenMeetingReferenceAreRejected() {
        val json = JSONObject(SchoolSnapshotCodec.encode(snapshot)); json.put("version", 99)
        try { SchoolSnapshotCodec.decode(json.toString()); fail("Unknown version accepted") } catch (_: IllegalArgumentException) { /* expected */ }
        json.put("version", 1); json.getJSONArray("doubts").getJSONObject(0).put("meeting", 5)
        try { SchoolSnapshotCodec.decode(json.toString()); fail("Invalid reference accepted") } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
