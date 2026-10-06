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
    val editing: Boolean = false,
    val editMessage: String = "",
    val editedId: UUID? = null,
    val createdTermId: UUID? = null,
    val editor: EditorSession? = null,
    val creatingTerm: Boolean = false,
    val adjustment: AdjustmentSession? = null,
    val management: CourseManagementSession? = null,
    val reviewSession: SavedSchedule? = null,
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
                        mutable.value.selected?.lastSuccessfulCheck?.let(policy::succeeded)
                        maybeCheck()
                    }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(loading = false, loadFailed = true, status = "本地课表读取失败，请重试。已有数据未被清除。") } }
        }
    }

    fun selectTerm(id: UUID) {
        if (!mutable.value.busy && !mutable.value.editing) {
            mutable.update { it.copy(selectedId = id) }
            mutable.value.selected?.lastSuccessfulCheck?.let(policy::succeeded)
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
        if (saved.scope == null) return
        // 只自动检查正在进行的学期；历史学期仍可离线查看。
        if (foreground && !mutable.value.loading && saved.term.weekOf(LocalDate.now(SchoolZone)) != null &&
            policy.shouldCheck(Instant.now(), online, editing = mutable.value.draft != null || mutable.value.editing, running = mutable.value.busy)
        ) foregroundGateway?.let { check(it, automatic = true) }
    }

    fun check(gateway: SchoolGateway, automatic: Boolean = false, useStoredConfig: Boolean = false) {
        val before = mutable.value
        if (before.busy || before.loading || before.loadFailed || before.editing) return
        if (useStoredConfig && before.selected?.scope == null) {
            mutable.update { it.copy(status = "此学期尚未接入学校，可在学校导入页确认接入。") }; return
        }
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
                // 完整有效变化落为待核对候选；成功获取计时与用户确认合并分开。
                if (checked.outcome != null) {
                    val result = checked.outcome
                    if (result is ImportOutcome.Saved) {
                        policy.succeeded(fetchedAt)
                        mutable.update { it.copy(running = false, draft = null, repeatedIdentically = true, status = "检查完成，学校数据与已保存基线一致。") }
                        return@launch
                    }
                    if (result == ImportOutcome.ChangedSourceNeedsReview) {
                        policy.succeeded(fetchedAt)
                        mutable.update { it.copy(running = false, draft = null, selectedId = it.schedules.firstOrNull { savedTerm -> savedTerm.scope == next.scope }?.id ?: it.selectedId,
                            status = "学校变化已保存到本机，原课表保留。请在“我的”中核对更新。") }
                        return@launch
                    }
                    status = "学校数据有变化，原课表已保留，请核对配置。"
                }
                policy.failed(fetchedAt, loginRequired = false)
                mutable.update { it.copy(running = false, draft = ImportDraft(UUID.randomUUID(), next, fetchedAt),
                    repeatedIdentically = before.draft?.snapshot?.sameContent(next), status = status) }
            } catch (_: ScheduleBusyException) { mutable.update { it.copy(status = "课表正在更新或保存，请稍后重试。原课表保留。") } } catch (_: TimeoutCancellationException) { failure(SchoolFailure.TIMEOUT) } catch (error: CancellationException) {
                mutable.update { it.copy(status = "获取已取消，课表和原预览保留。") }; throw error
            } catch (error: SchoolException) { failure(error.failure) } catch (_: Exception) { failure(SchoolFailure.INVALID_DATA) } finally { mutable.update { it.copy(running = false) } }
        }
    }

    fun save(draftId: UUID, term: Term, periods: Map<Int, TimeRange>, acknowledged: Set<Int>, attachToLocalTerm: UUID? = null) {
        val current = mutable.value
        val draft = current.draft?.takeIf { it.id == draftId } ?: return
        if (current.busy || current.editing) return
        mutable.update { it.copy(saving = true, status = "正在保存到本机…") }
        viewModelScope.launch {
            try {
                val result = repository.confirmImport(ImportPlan(draft.snapshot, term, periods, acknowledged, draft.fetchedAt, attachToLocalTerm))
                when (result) {
                    is ImportOutcome.Saved -> {
                        policy.succeeded(draft.fetchedAt)
                        mutable.update { it.copy(draft = null, selectedId = result.termId, importedId = result.termId,
                            status = "已保存，关闭应用后仍可离线查看。") }
                    }
                    ImportOutcome.ChangedSourceNeedsReview -> mutable.update { it.copy(draft = null,
                        selectedId = it.schedules.firstOrNull { savedTerm -> savedTerm.scope == draft.snapshot.scope }?.id ?: it.selectedId,
                        status = "变化已保存，请在“我的”中核对学校更新。原课表保留。") }
                    ImportOutcome.DifferentAccount -> mutable.update { it.copy(status = "学校账号与已保存课表不同，已拒绝混入。") }
                    ImportOutcome.DifferentTermConfiguration -> mutable.update { it.copy(status = "学期起点或作息与已保存配置不同，原课表保留。") }
                    ImportOutcome.LocalTermConfirmationRequired -> mutable.update { it.copy(status = "此学期已有手工课程，请确认保留它们并接入学校课表。") }
                    ImportOutcome.StaleSnapshot -> mutable.update { it.copy(status = "这份获取结果早于已确认的数据，请重新获取。原课表保留。") }
                }
            } catch (_: ScheduleBusyException) { mutable.update { it.copy(status = "课表正在更新，稍后再确认导入。原课表保留。") } } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(status = "未能保存，请检查日期、周数、作息和核对项。原课表未改变。") } } finally { mutable.update { it.copy(saving = false) } }
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

    fun beginEditing(): Boolean {
        val current = mutable.value
        if (current.busy || current.loading || current.loadFailed) return false
        mutable.update { it.copy(editing = true, editMessage = "", editedId = null, createdTermId = null) }
        return true
    }
    fun endEditing() {
        if (!mutable.value.saving) { mutable.update { it.copy(editing = false, editor = null, adjustment = null, management = null, reviewSession = null, creatingTerm = false, editMessage = "") }; maybeCheck() }
    }
    fun openEditor(id: UUID?) {
        val saved = mutable.value.selected ?: return
        val initial = id?.let { courseId -> saved.arrangements.firstOrNull { it.id == courseId } ?: return }
        if (beginEditing()) mutable.update { it.copy(editor = EditorSession(saved, initial), adjustment = null, management = null, creatingTerm = false) }
    }
    fun openManualTerm() { if (beginEditing()) mutable.update { it.copy(editor = null, adjustment = null, management = null, creatingTerm = true) } }
    fun saveEdit(saved: SavedSchedule, edit: ArrangementEdit) {
        if (mutable.value.busy || !mutable.value.editing) return
        mutable.update { it.copy(saving = true, editMessage = "正在保存…") }
        viewModelScope.launch {
            try {
                when (val result = repository.saveArrangement(saved.id, saved.revision, edit)) {
                    is EditOutcome.Saved -> mutable.update { it.copy(editedId = result.arrangementId,
                        status = if (result.overlappingArrangements == 0) "课程已保存。" else "课程已保存，与 ${result.overlappingArrangements} 条安排时间重叠。", editMessage = "") }
                    EditOutcome.Busy -> mutable.update { it.copy(editMessage = "课表正在更新，稍后再保存。") }
                    EditOutcome.Stale -> mutable.update { it.copy(editMessage = "课表已变化，请返回并重新打开编辑。此次未写入。") }
                    EditOutcome.Missing -> mutable.update { it.copy(editMessage = "这条安排已不可编辑，请返回刷新。") }
                    EditOutcome.Invalid -> mutable.update { it.copy(editMessage = "请检查课程名、周次和时间范围。") }
                    EditOutcome.ExceptionReviewRequired -> mutable.update { it.copy(editMessage = "调整会影响已有单次例外，请先处理相关例外。此次未写入。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(editMessage = "保存失败，原课程未改变。") } } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun createManualTerm(plan: ManualTermPlan) {
        if (mutable.value.busy || !mutable.value.editing) return
        mutable.update { it.copy(saving = true, editMessage = "正在建立学期…") }
        viewModelScope.launch {
            try {
                when (val result = repository.createManualTerm(plan)) {
                    is ManualTermOutcome.Saved -> mutable.update { it.copy(selectedId = result.termId, createdTermId = result.termId, editMessage = "") }
                    ManualTermOutcome.DifferentConfiguration -> mutable.update { it.copy(editMessage = "同一学期已有不同配置，请返回查看原学期。此次未改变原数据。") }
                    ManualTermOutcome.Busy -> mutable.update { it.copy(editMessage = "课表正在更新，稍后再建立学期。") }
                    ManualTermOutcome.Invalid -> mutable.update { it.copy(editMessage = "请检查学年、起点、周数和作息。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(editMessage = "建立失败，原课表保留。") } } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun consumeEdited() { mutable.update { it.copy(editedId = null, editing = false, editor = null, adjustment = null, management = null, creatingTerm = false) }; maybeCheck() }

    fun openSchoolReview() {
        val saved = mutable.value.selected?.takeIf { it.schoolReview != null } ?: return
        if (beginEditing()) mutable.update { it.copy(reviewSession = saved) }
    }
    fun confirmSchoolReview(plan: SchoolReviewPlan) {
        if (mutable.value.busy || !mutable.value.editing) return
        mutable.update { it.copy(saving = true, editMessage = "正在确认更新…") }
        viewModelScope.launch {
            try {
                when (val result = repository.confirmSchoolReview(plan)) {
                    is ReviewOutcome.Saved -> {
                        mutable.value.reviewSession?.schoolReview?.fetchedAt?.let(policy::succeeded)
                        mutable.update { it.copy(reviewSession = null, editing = false, draft = null, editMessage = "",
                            status = "学校更新已确认。" + if (result.orphanedExceptions > 0) "有 ${result.orphanedExceptions} 项单次调整关联待核对，可在我的中处理。" else "") }
                    }
                    ReviewOutcome.Busy -> mutable.update { it.copy(editMessage = "课表正在更新，请稍后重试。") }
                    ReviewOutcome.Stale -> mutable.update { it.copy(editMessage = "课表或学校候选已变化，请返回重新核对。此次未写入。") }
                    ReviewOutcome.Invalid -> mutable.update { it.copy(editMessage = "请完整确认配对、删除、缺失项及每个字段冲突。此次未写入。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) {
                mutable.update { it.copy(editMessage = "更新未完成，原课表及学校候选保留，请重试。") }
            } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun consumeCreatedTerm() { mutable.update { it.copy(createdTermId = null) } }

    fun openAdjustment(id: UUID, originalDate: LocalDate) {
        val saved = mutable.value.selected ?: return
        val course = saved.arrangements.firstOrNull { it.id == id } ?: return
        if (beginEditing()) mutable.update { it.copy(editor = null, adjustment = AdjustmentSession(saved, course, originalDate), management = null, creatingTerm = false) }
    }
    fun saveAdjustment(session: AdjustmentSession, next: SingleException?) {
        if (mutable.value.busy || !mutable.value.editing) return
        mutable.update { it.copy(saving = true, editMessage = "正在保存本次调整…") }
        viewModelScope.launch {
            try {
                val result = if (next == null) repository.clearSingleException(session.schedule.id, session.schedule.revision, session.course.id, session.originalDate)
                    else repository.saveSingleException(session.schedule.id, session.schedule.revision, next)
                when (result) {
                    is EditOutcome.Saved -> {
                        val label = when (next) { null -> "本次调整已撤销。"; is SingleException.Cancel -> "本次停课已保存。"; is SingleException.Move -> "本次调课已保存。" }
                        mutable.update { it.copy(editedId = result.arrangementId, status = label + if (result.overlappingArrangements > 0) "与 ${result.overlappingArrangements} 次课程重叠。" else "", editMessage = "") }
                    }
                    EditOutcome.Busy -> mutable.update { it.copy(editMessage = "课表正在更新，稍后重试。") }
                    EditOutcome.Stale -> mutable.update { it.copy(editMessage = "课表已变化，请返回重新打开。此次未写入。") }
                    EditOutcome.Missing -> mutable.update { it.copy(editMessage = "课程或调整已变化，请返回核对。") }
                    EditOutcome.Invalid, EditOutcome.ExceptionReviewRequired -> mutable.update { it.copy(editMessage = "原日期、调至日期或时间不符合当前学期，请核对。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(editMessage = "保存失败，原课表及调整保留。") } } finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun openRemoval(id: UUID) {
        val saved = mutable.value.selected ?: return
        val course = saved.arrangements.firstOrNull { it.id == id } ?: return
        if (beginEditing()) mutable.update { it.copy(editor = null, adjustment = null, creatingTerm = false, management = CourseManagementSession(saved, course, false)) }
    }
    fun openRestoration(id: UUID) {
        val saved = mutable.value.selected ?: return
        if (saved.schoolReview != null) { mutable.update { it.copy(status = "学校有更新待核对，请先确认更新，再预览并恢复隐藏课程。") }; return }
        val hidden = saved.hiddenSchoolCourses.firstOrNull { it.arrangement.id == id } ?: return
        if (beginEditing()) mutable.update { it.copy(editor = null, adjustment = null, creatingTerm = false, management = CourseManagementSession(saved, hidden.arrangement, true, hidden)) }
    }
    fun confirmManagement(session: CourseManagementSession) {
        if (mutable.value.busy || !mutable.value.editing) return
        mutable.update { it.copy(saving = true, editMessage = "正在保存…") }
        viewModelScope.launch {
            try {
                val result = if (session.restore) repository.restoreArrangement(session.schedule.id, session.schedule.revision, session.course.id)
                    else repository.removeArrangement(session.schedule.id, session.schedule.revision, session.course.id)
                when (result) {
                    is EditOutcome.Saved -> {
                        val status = if (session.restore) "课程已恢复。" else if (session.schedule.origins[session.course.id] == CourseOrigin.SCHOOL) "课程已隐藏，来源和修改保留。" else "手工课程已删除。"
                        mutable.update { it.copy(editedId = result.arrangementId, status = status + if (result.overlappingArrangements > 0) "恢复后与 ${result.overlappingArrangements} 条安排重叠。" else "", editMessage = "") }
                    }
                    EditOutcome.Busy -> mutable.update { it.copy(editMessage = "课表正在更新，稍后重试。") }
                    EditOutcome.Stale -> mutable.update { it.copy(editMessage = "课表已变化，请取消并重新打开确认。此次未写入。") }
                    EditOutcome.Missing -> mutable.update { it.copy(editMessage = "课程状态已变化，请返回核对。") }
                    EditOutcome.ExceptionReviewRequired -> mutable.update { it.copy(editMessage = "学校更新待核对，请返回确认更新后再恢复。原课程保留。") }
                    EditOutcome.Invalid -> mutable.update { it.copy(editMessage = "当前数据需要核对，原课程未改变。") }
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { mutable.update { it.copy(editMessage = "保存失败，原课程和关联数据保留。") } } finally { mutable.update { it.copy(saving = false) } }
        }
    }
}
