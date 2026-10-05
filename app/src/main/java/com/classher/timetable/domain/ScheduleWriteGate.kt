package com.classher.timetable.domain

import kotlinx.coroutines.sync.Mutex

/** 所有课程写入和学校获取共用此入口；网络阶段不持有 Room 事务。 */
class ScheduleWriteGate {
    private val mutex = Mutex()
    suspend fun <T> write(block: suspend () -> T): T = tryWrite(block)
    suspend fun <T> tryWrite(block: suspend () -> T): T {
        if (!mutex.tryLock()) throw ScheduleBusyException()
        try { return block() } finally { mutex.unlock() }
    }
}

class ScheduleBusyException : IllegalStateException("Schedule update or write is running")
