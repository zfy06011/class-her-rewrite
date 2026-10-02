package com.classher.timetable.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.classher.timetable.domain.CheckPolicy
import com.classher.timetable.domain.SchoolException
import com.classher.timetable.domain.SchoolFailure
import com.classher.timetable.domain.SchoolGateway
import com.classher.timetable.domain.SchoolParser
import com.classher.timetable.domain.SchoolSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

data class ProbeState(
    val running: Boolean = false,
    val snapshot: SchoolSnapshot? = null,
    val status: String = "登录学校后，打开教学安排中的个人课表，再获取当前学期。",
    val repeatedIdentically: Boolean? = null,
    val weekCount: Int = 19,
    val periodsPerDay: Int = 12,
)

@HiltViewModel
class ProbeViewModel @Inject constructor(private val parser: SchoolParser) : ViewModel() {
    private val mutableState = MutableStateFlow(ProbeState())
    val state = mutableState.asStateFlow()
    private val policy = CheckPolicy()
    private var job: Job? = null

    fun configure(weeks: Int, periods: Int) {
        if (!mutableState.value.running && weeks in 1..60 && periods in 1..48) {
            mutableState.value = mutableState.value.copy(weekCount = weeks, periodsPerDay = periods)
        }
    }

    fun onForeground(gateway: SchoolGateway, online: Boolean) {
        // 正式计时持久化等待数据库阶段；验证包只在已有本次进程预览时尝试。
        if (mutableState.value.snapshot != null && policy.shouldCheck(
                Instant.now(), online, editing = false, running = mutableState.value.running,
            )) check(gateway)
    }

    fun check(gateway: SchoolGateway) {
        if (mutableState.value.running) return
        mutableState.value = mutableState.value.copy(running = true, status = "正在获取并解析，原预览保持可见…")
        job = viewModelScope.launch {
            try {
                val response = gateway.fetch()
                val config = mutableState.value
                val next = withContext(Dispatchers.Default) {
                    parser.parse(response.html, response.scope, config.weekCount, config.periodsPerDay)
                }
                val previous = config.snapshot
                if (previous != null && previous.scope != next.scope) {
                    throw SchoolException(SchoolFailure.IDENTITY_MISMATCH)
                }
                // 最小验证不落库；有疑点也不能推进正式成功检查计时。
                if (next.readyForConfirmation) policy.succeeded(Instant.now())
                else policy.failed(Instant.now(), loginRequired = false)
                mutableState.value = config.copy(
                    running = false, snapshot = next,
                    repeatedIdentically = previous?.sameContent(next),
                    status = if (next.doubts.isEmpty()) "完整获取与解析完成；尚未写入正式课表。"
                        else "已获取，仍有 ${next.doubts.size} 个核对提示；尚未写入正式课表。",
                )
            } catch (_: TimeoutCancellationException) {
                failure(SchoolFailure.TIMEOUT)
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(running = false, status = "获取已取消，原预览保留。")
                throw error
            } catch (error: SchoolException) {
                failure(error.failure)
            } catch (_: Exception) {
                failure(SchoolFailure.INVALID_DATA)
            } finally {
                mutableState.value = mutableState.value.copy(running = false)
            }
        }
    }

    private fun failure(reason: SchoolFailure) {
        policy.failed(Instant.now(), reason == SchoolFailure.LOGIN_REQUIRED)
        val message = when (reason) {
            SchoolFailure.LOGIN_REQUIRED -> "请登录学校并打开当前学期的个人课表。"
            SchoolFailure.NETWORK -> "网络获取失败，可以稍后重试。"
            SchoolFailure.TIMEOUT -> "获取超时，可以重试。"
            SchoolFailure.IDENTITY_MISMATCH -> "来源账号或学期不一致。请核对学校所选学期，清除预览后重新获取。"
            SchoolFailure.EMPTY -> "返回异常空课表，已拒绝覆盖。"
            SchoolFailure.INCOMPLETE -> "响应或星期网格不完整，已拒绝覆盖。"
            SchoolFailure.CANCELLED -> "获取已取消。"
            SchoolFailure.NOT_TIMETABLE -> "返回的页面不是受支持的完整课表。"
            SchoolFailure.INVALID_DATA -> "数据解析未通过，请核对学期周数和每日节次。"
        }
        mutableState.value = mutableState.value.copy(running = false, status = "$message 原预览保留。")
    }

    fun cancel() { job?.cancel() }
    fun clearPreview() {
        if (!mutableState.value.running) mutableState.value = mutableState.value.copy(
            snapshot = null, repeatedIdentically = null, status = "预览已清除，可重新获取。",
        )
    }
}
