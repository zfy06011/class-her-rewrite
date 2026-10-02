package com.classher.timetable.domain

import java.time.Duration
import java.time.Instant

/** 自动检查只在应用前台触发；失败不改变成功时间，编辑期间延后。 */
class CheckPolicy(private val retryDelay: Duration = Duration.ofMinutes(15)) {
    var lastSuccess: Instant? = null
        private set
    private var retryAfter: Instant? = null
    private var needsLogin = false

    fun shouldCheck(now: Instant, online: Boolean, editing: Boolean, running: Boolean): Boolean =
        online && !editing && !running && !needsLogin &&
            (retryAfter == null || !now.isBefore(retryAfter)) &&
            (lastSuccess == null || Duration.between(lastSuccess, now) > Duration.ofHours(6))

    fun succeeded(now: Instant) { lastSuccess = now; retryAfter = null; needsLogin = false }
    fun failed(now: Instant, loginRequired: Boolean) {
        retryAfter = now.plus(retryDelay)
        needsLogin = loginRequired
    }
    fun loginCompleted() { needsLogin = false; retryAfter = null }
}
