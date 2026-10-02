package com.classher.timetable

import com.classher.timetable.domain.ConflictChoice
import com.classher.timetable.domain.FieldMerge
import com.classher.timetable.domain.LocalOverride
import com.classher.timetable.domain.StaleConflictException
import com.classher.timetable.domain.VersionedFieldConflict
import com.classher.timetable.domain.mergeField
import com.classher.timetable.domain.refreshConflict
import com.classher.timetable.domain.resolveFieldConflict
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldMergeTest {
    private val pending = FieldMerge.Conflict("原教室", "本地教室", "学校新教室")

    @Test fun teacherAndRoomCanMergeIndependently() {
        val teacher = mergeField("原教师", LocalOverride.None, "新教师")
        val room = mergeField("原教室", LocalOverride.Value("本地教室"), "原教室")
        assertEquals(FieldMerge.Merged("新教师", "新教师", LocalOverride.None), teacher)
        assertEquals(FieldMerge.Merged("原教室", "本地教室", LocalOverride.Value("本地教室")), room)
    }

    @Test fun equalChangesDoNotConflictAndRedundantOverrideIsCleared() {
        assertEquals(FieldMerge.Merged("同一新值", "同一新值", LocalOverride.None),
            mergeField("原值", LocalOverride.Value("同一新值"), "同一新值"))
        assertEquals(FieldMerge.Merged("学校新值", "学校新值", LocalOverride.None),
            mergeField("原值", LocalOverride.Value("原值"), "学校新值"))
    }

    @Test fun differingChangesPreserveAllComparisonValues() {
        assertEquals(pending, mergeField("原教室", LocalOverride.Value("本地教室"), "学校新教室"))
    }

    @Test fun explicitBlankAndNullAreLocalValues() {
        assertEquals(FieldMerge.Merged("原教室", "", LocalOverride.Value("")),
            mergeField("原教室", LocalOverride.Value(""), "原教室"))
        assertEquals(FieldMerge.Conflict("原教室", null, "学校新教室"),
            mergeField<String?>("原教室", LocalOverride.Value(null), "学校新教室"))
    }

    @Test fun repeatedPendingCandidateKeepsConflictAndOriginalBasis() {
        assertEquals(pending, refreshConflict(pending, "学校新教室"))
        assertEquals(FieldMerge.Conflict("原教室", "本地教室", "学校再改教室"),
            refreshConflict(pending, "学校再改教室"))
    }

    @Test fun schoolRevertOrConvergenceResolvesPendingComparison() {
        assertEquals(FieldMerge.Merged("原教室", "本地教室", LocalOverride.Value("本地教室")),
            refreshConflict(pending, "原教室"))
        assertEquals(FieldMerge.Merged("本地教室", "本地教室", LocalOverride.None),
            refreshConflict(pending, "本地教室"))
    }

    @Test fun currentConfirmationKeepsLocalOrAdoptsSchool() {
        val selected = VersionedFieldConflict(pending, 3L)
        assertEquals(FieldMerge.Merged("学校新教室", "本地教室", LocalOverride.Value("本地教室")),
            resolveFieldConflict(selected, selected, ConflictChoice.KEEP_LOCAL))
        assertEquals(FieldMerge.Merged("学校新教室", "学校新教室", LocalOverride.None),
            resolveFieldConflict(selected, selected, ConflictChoice.USE_SCHOOL))
    }

    @Test(expected = StaleConflictException::class) fun staleVersionCannotConfirmEvenEqualValues() {
        resolveFieldConflict(VersionedFieldConflict(pending, 3L), VersionedFieldConflict(pending, 4L), ConflictChoice.KEEP_LOCAL)
    }

    @Test(expected = StaleConflictException::class) fun changedCandidateCannotBeConfirmedWithOldChoice() {
        val changed = pending.copy(incomingSchool = "学校再改教室")
        resolveFieldConflict(VersionedFieldConflict(pending, 3L), VersionedFieldConflict(changed, 3L), ConflictChoice.USE_SCHOOL)
    }

    @Test(expected = StaleConflictException::class) fun reeditedLocalValueInvalidatesOldChoice() {
        val edited = pending.copy(localValue = "本地再次修改")
        resolveFieldConflict(VersionedFieldConflict(pending, 3L), VersionedFieldConflict(edited, 3L), ConflictChoice.KEEP_LOCAL)
    }
}
