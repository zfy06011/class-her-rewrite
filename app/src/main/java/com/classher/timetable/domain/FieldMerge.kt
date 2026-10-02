package com.classher.timetable.domain

/** 未覆盖与显式空值分开；泛型值可以是空字符串，也可以是 nullable 类型。 */
sealed interface LocalOverride<out T> {
    data object None : LocalOverride<Nothing>
    data class Value<T>(val value: T) : LocalOverride<T>
}

sealed interface FieldMerge<out T> {
    data class Merged<T>(
        val schoolBaseline: T,
        val visibleValue: T,
        val localOverride: LocalOverride<T>,
    ) : FieldMerge<T>

    /** 保留完整比较依据；不能只保留新基线再用它重新判断旧冲突。 */
    data class Conflict<T>(
        val previousSchool: T,
        val localValue: T,
        val incomingSchool: T,
    ) : FieldMerge<T>
}

/** 字段或已确认原子字段组的三方比较；不负责身份匹配或写入数据库。 */
fun <T> mergeField(previousSchool: T, local: LocalOverride<T>, incomingSchool: T): FieldMerge<T> =
    when (local) {
        LocalOverride.None -> FieldMerge.Merged(incomingSchool, incomingSchool, LocalOverride.None)
        is LocalOverride.Value -> when {
            local.value == incomingSchool || local.value == previousSchool ->
                FieldMerge.Merged(incomingSchool, incomingSchool, LocalOverride.None)
            incomingSchool == previousSchool ->
                FieldMerge.Merged(incomingSchool, local.value, local)
            else -> FieldMerge.Conflict(previousSchool, local.value, incomingSchool)
        }
    }

/** 未决冲突再次检查时延续原比较依据；候选变化、学校回退或双方收敛均重新判断。 */
fun <T> refreshConflict(conflict: FieldMerge.Conflict<T>, incomingSchool: T): FieldMerge<T> =
    mergeField(conflict.previousSchool, LocalOverride.Value(conflict.localValue), incomingSchool)

data class VersionedFieldConflict<T, V : Any>(val comparison: FieldMerge.Conflict<T>, val version: V)

enum class ConflictChoice { KEEP_LOCAL, USE_SCHOOL }

class StaleConflictException : IllegalStateException("Field conflict changed; reload before confirming")

/**
 * 调用方须在统一写入入口的短事务内提供当前权威版本；此函数不替代数据库事务或写入锁。
 * 版本 token 的生成与保存留给仓库，不能用页面缓存作为 current。
 */
fun <T, V : Any> resolveFieldConflict(
    selected: VersionedFieldConflict<T, V>,
    current: VersionedFieldConflict<T, V>,
    choice: ConflictChoice,
): FieldMerge.Merged<T> {
    if (selected != current) throw StaleConflictException()
    val comparison = current.comparison
    return when (choice) {
        ConflictChoice.KEEP_LOCAL -> FieldMerge.Merged(
            comparison.incomingSchool, comparison.localValue, LocalOverride.Value(comparison.localValue),
        )
        ConflictChoice.USE_SCHOOL -> FieldMerge.Merged(
            comparison.incomingSchool, comparison.incomingSchool, LocalOverride.None,
        )
    }
}
