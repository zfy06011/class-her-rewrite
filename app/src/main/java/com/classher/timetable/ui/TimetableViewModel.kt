package com.classher.timetable.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.classher.timetable.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class ImportDraft(val id: UUID, val snapshot: SchoolSnapshot, val fetchedAt: Instant)
data class TimetableState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val schedules: List<SavedSchedule> = emptyList(),
    val selectedId: UUID? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val running: Boolean = false,
    val saving: Boolean = false,
    val draft: ImportDraft? = null,
    val repeatedIdentically: Boolean? = null,
    val status: String = "登录学校后打开个人课表，获取并确认导入。",
    val weekCount: Int = 19,
    val periodsPerDay: Int = 12,
    val importedId: UUID? = null,
) {
    val selected: SavedSchedule? get() = schedules.firstOrNull { it.id == selectedId } ?: schedules.firstOrNull()
    val busy: Boolean get() = running || saving
}

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val parser: SchoolParser,
    private val repository: ScheduleRepository,
    private val settings: AppSettings,
) : ViewModel() {
    private val mutable = MutableStateFlow(TimetableState())
    val state = mutable.asStateFlow()
    private val policy = CheckPolicy()
    private var fetchJob: Job? = null
    private var loadJob: Job? = null
    private var foregroundGateway: SchoolGateway? = null
    private var online = false
    private var foreground = false

    init {
        load()
        viewModelScope.launch {
            try { settings.theme.collect { theme -> mutable.update { it.copy(theme = theme) } } } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { mutable.update { it.copy(status = "外观设置读取失败，可继续查看课表。") } }
        }
    }

    fun load() {
        loadJob?.cancel()
        mutable.update { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                var first = true
                repository.observeSchedules().collect { schedules ->
                    val today = LocalDate.now(SchoolZone)
                    val id = mutable.value.selectedId?.takeIf { selected -> schedules.any { it.id == selected } }
                        ?: schedules.firstOrNull { it.term.weekOf(today) != null }?.id ?: schedules.firstOrNull()?.id
                    mutable.update { it.copy(loading = false, loadFailed = false, schedules = schedules, selectedId = id) }
                    if (first) {
                        first = false
                        mutable.value.selected?.let { policy.succeeded(it.lastSuccessfulCheck) }
                        maybeCheck()
                    }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(loading = false, loadFailed = true, status = "本地课表读取失败，请重试。已有数据未被清除。") } }
        }
    }

    fun selectTerm(id: UUID) {
        if (!mutable.value.busy) {
            mutable.update { it.copy(selectedId = id) }
            mutable.value.selected?.let { policy.succeeded(it.lastSuccessfulCheck) }
        }
    }
    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch {
            try { settings.setTheme(theme) } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(status = "外观设置未保存，请重试。") } }
        }
    }
    fun configure(weeks: Int, periods: Int) {
        if (!mutable.value.busy && weeks in 1..60 && periods in 1..48) mutable.update { it.copy(weekCount = weeks, periodsPerDay = periods) }
    }
    fun onForeground(gateway: SchoolGateway, hasNetwork: Boolean) {
        foregroundGateway = gateway; online = hasNetwork; foreground = true
        maybeCheck()
    }
    fun onBackground() { foreground = false; cancelFetch() }
    private fun maybeCheck() {
        val saved = mutable.value.selected ?: return
        // 只自动检查正在进行的学期；历史学期仍可离线查看。
        if (foreground && !mutable.value.loading && saved.term.weekOf(LocalDate.now(SchoolZone)) != null &&
            policy.shouldCheck(Instant.now(), online, editing = mutable.value.draft != null, running = mutable.value.busy)
        ) foregroundGateway?.let { check(it, automatic = true) }
    }

    fun check(gateway: SchoolGateway, automatic: Boolean = false, useStoredConfig: Boolean = false) {
        val before = mutable.value
        if (before.busy || before.loading || before.loadFailed) return
        mutable.update { it.copy(running = true, status = "正在获取并解析，已保存课表仍可查看…", importedId = null) }
        fetchJob = viewModelScope.launch {
            try {
                val saved = before.selected
                val useSaved = automatic || useStoredConfig
                val weeks = if (useSaved && saved != null) saved.term.weekCount else before.weekCount
                val periods = if (useSaved && saved != null) saved.periods.size else before.periodsPerDay
                val checked = repository.checkSource(if (useSaved) saved?.scope else null) {
                    val response = gateway.fetch()
                    withContext(Dispatchers.Default) { parser.parse(response.html, response.scope, weeks, periods) }
                }
                val next = checked.snapshot
                if (checked.outcome == ImportOutcome.DifferentAccount) throw SchoolException(SchoolFailure.IDENTITY_MISMATCH)
                val fetchedAt = checked.fetchedAt
                var status = "完整获取：请核对学期、作息和每个缺失项后保存。"
                // 已确认基线完全一致才更新成功时间；变化内容不会盲目覆盖本地课程。
                if (checked.outcome != null) {
                    val result = checked.outcome
                    if (result is ImportOutcome.Saved) {
                        policy.succeeded(fetchedAt)
                        mutable.update { it.copy(running = false, draft = null, repeatedIdentically = true, status = "检查完成，学校数据与已保存基线一致。") }
                        return@launch
                    }
                    status = "学校数据有变化，原课表已保留。变更关联与合并将在后续功能中处理。"
                }
                policy.failed(fetchedAt, loginRequired = false)
                mutable.update { it.copy(running = false, draft = ImportDraft(UUID.randomUUID(), next, fetchedAt),
                    repeatedIdentically = before.draft?.snapshot?.sameContent(next), status = status) }
            } catch (_: TimeoutCancellationException) { failure(SchoolFailure.TIMEOUT) } catch (error: CancellationException) {
                mutable.update { it.copy(status = "获取已取消，课表和原预览保留。") }; throw error
            } catch (error: SchoolException) { failure(error.failure) } catch (_: Exception) { failure(SchoolFailure.INVALID_DATA) } finally { mutable.update { it.copy(running = false) } }
        }
    }

    fun save(draftId: UUID, term: Term, periods: Map<Int, TimeRange>, acknowledged: Set<Int>) {
        val current = mutable.value
        val draft = current.draft?.takeIf { it.id == draftId } ?: return
        if (current.busy) return
        mutable.update { it.copy(saving = true, status = "正在保存到本机…") }
        viewModelScope.launch {
            try {
                val result = repository.confirmImport(ImportPlan(draft.snapshot, term, periods, acknowledged, draft.fetchedAt))
                when (result) {
                    is ImportOutcome.Saved -> {
                        policy.succeeded(draft.fetchedAt)
                        mutable.update { it.copy(draft = null, selectedId = result.termId, importedId = result.termId,
                            status = "已保存，关闭应用后仍可离线查看。") }
                    }
                    ImportOutcome.ChangedSourceNeedsReview -> mutable.update { it.copy(status = "同学期来源已变化，原课表保留。请等待变更核对功能。") }
                    ImportOutcome.DifferentAccount -> mutable.update { it.copy(status = "学校账号与已保存课表不同，已拒绝混入。") }
                    ImportOutcome.DifferentTermConfiguration -> mutable.update { it.copy(status = "学期起点或作息与已保存配置不同，原课表保留。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(status = "未能保存，请检查日期、周数、作息和核对项。原课表未改变。") } } finally { mutable.update { it.copy(saving = false) } }
        }
    }

    private fun failure(reason: SchoolFailure) {
        policy.failed(Instant.now(), reason == SchoolFailure.LOGIN_REQUIRED)
        val message = when (reason) {
            SchoolFailure.LOGIN_REQUIRED -> "请登录学校并打开当前学期个人课表。"
            SchoolFailure.NETWORK -> "网络获取失败，可稍后重试。"
            SchoolFailure.TIMEOUT -> "获取超时，可重试。"
            SchoolFailure.IDENTITY_MISMATCH -> "来源账号或学期不同，请核对学校所选学期。"
            SchoolFailure.EMPTY -> "返回异常空课表，已拒绝覆盖。"
            SchoolFailure.INCOMPLETE -> "响应或星期网格不完整，已拒绝覆盖。"
            SchoolFailure.CANCELLED -> "获取已取消。"
            SchoolFailure.NOT_TIMETABLE -> "返回的页面不是受支持的完整课表。"
            SchoolFailure.INVALID_DATA -> "解析或保存未通过，请核对周数和每日节次。"
        }
        mutable.update { it.copy(status = "$message 本地课表和原预览保留。") }
    }
    fun cancelFetch() { fetchJob?.cancel() }
    fun clearPreview() { if (!mutable.value.busy) mutable.update { it.copy(draft = null, repeatedIdentically = null, status = "预览已清除，本地课表保留。") } }
    fun schoolSessionCleared() { policy.failed(Instant.now(), loginRequired = true) }
    fun consumeImported() { mutable.update { it.copy(importedId = null) } }
}
