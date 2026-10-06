package com.classher.timetable.data.local

import com.classher.timetable.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** 仅持久化统一业务字段；不保存 HTML、会话、账号原值。 */
object SchoolSnapshotCodec {
    fun encode(snapshot: SchoolSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("digest", snapshot.scope.accountDigest); put("year", snapshot.scope.year); put("semester", snapshot.scope.semester)
        put("meetings", JSONArray().apply { snapshot.meetings.forEach { add -> put(JSONObject().apply {
            put("name", add.name); put("teacher", add.teacher); put("room", add.room); put("weekday", add.weekday)
            put("weeks", JSONArray(add.weeks.sorted())); put("periods", JSONArray(add.periods.sorted()))
        }) } })
        put("unscheduled", JSONArray(snapshot.unscheduledNames))
        put("doubts", JSONArray().apply { snapshot.doubts.forEach { add -> put(JSONObject().apply {
            put("reason", add.reason); put("kind", add.kind.name)
            put("day", add.weekday ?: JSONObject.NULL); put("big", add.bigPeriod ?: JSONObject.NULL)
            put("meeting", add.meeting?.let(snapshot.meetings::indexOf) ?: -1)
        }) } })
    }.toString()

    fun decode(text: String): SchoolSnapshot {
        val json = JSONObject(text); require(json.getInt("version") == 1)
        val meetings = json.getJSONArray("meetings").let { array -> (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            ParsedMeeting(row.getString("name"), row.getString("teacher"), row.getString("room"), row.getInt("weekday"),
                numbers(row.getJSONArray("weeks")), numbers(row.getJSONArray("periods")))
        } }
        val unscheduled = json.getJSONArray("unscheduled").let { array -> (0 until array.length()).map(array::getString) }
        val doubts = json.getJSONArray("doubts").let { array -> (0 until array.length()).map { index ->
            val row = array.getJSONObject(index); val meeting = row.getInt("meeting")
            require(meeting == -1 || meeting in meetings.indices)
            ParseDoubt(row.getString("reason"), if (row.isNull("day")) null else row.getInt("day"),
                if (row.isNull("big")) null else row.getInt("big"), DoubtKind.valueOf(row.getString("kind")), meetings.getOrNull(meeting))
        } }
        return SchoolSnapshot(SourceScope(json.getString("digest"), json.getString("year"), json.getString("semester")), meetings, unscheduled, doubts)
    }
    private fun numbers(array: JSONArray): Set<Int> = (0 until array.length()).map(array::getInt).toSet()
}
