package com.classher.timetable.domain

data class RawSchoolResponse(val html: String, val scope: SourceScope)

interface SchoolGateway {
    suspend fun fetch(): RawSchoolResponse
}
