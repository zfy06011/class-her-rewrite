package com.classher.timetable.domain

import java.security.MessageDigest

fun accountDigest(account: String): String = MessageDigest.getInstance("SHA-256")
    .digest(account.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

// 来源作用域只保存账号摘要；不在业务模型中保存会话或原始 HTML。
data class SourceScope(val accountDigest: String, val year: String, val semester: String)

data class ParsedMeeting(
    val name: String,
    val teacher: String,
    val room: String,
    val weekday: Int,
    val weeks: Set<Int>,
    val periods: Set<Int>,
)

enum class DoubtKind { MISSING_ROOM, UNSCHEDULED, OTHER }

data class ParseDoubt(
    val reason: String,
    val weekday: Int? = null,
    val bigPeriod: Int? = null,
    val kind: DoubtKind = DoubtKind.OTHER,
    val meeting: ParsedMeeting? = null,
)

data class SchoolSnapshot(
    val scope: SourceScope,
    val meetings: List<ParsedMeeting>,
    val unscheduledNames: List<String>,
    val doubts: List<ParseDoubt>,
) {
    val readyForConfirmation: Boolean get() = doubts.isEmpty()
    fun sameContent(other: SchoolSnapshot): Boolean =
        scope == other.scope && meetings.toSet() == other.meetings.toSet() &&
            unscheduledNames.toSet() == other.unscheduledNames.toSet() && doubts == other.doubts
}

enum class SchoolFailure {
    LOGIN_REQUIRED, NETWORK, TIMEOUT, CANCELLED, NOT_TIMETABLE, INCOMPLETE,
    EMPTY, INVALID_DATA, IDENTITY_MISMATCH,
}

class SchoolException(val failure: SchoolFailure) : Exception(failure.name)

interface SchoolParser {
    fun parse(html: String, expected: SourceScope, weekCount: Int, periodsPerDay: Int): SchoolSnapshot
}
