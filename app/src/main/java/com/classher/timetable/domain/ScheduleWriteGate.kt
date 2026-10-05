package com.classher.timetable.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 所有课程写入和学校获取共用此入口；网络阶段不持有 Room 事务。 */
class ScheduleWriteGate {
    private val mutex = Mutex()
    suspend fun <T> write(block: suspend () -> T): T = mutex.withLock { block() }
}
