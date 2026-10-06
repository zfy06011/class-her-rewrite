package com.classher.timetable.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "terms", indices = [Index(value = ["school", "accountDigest", "year", "semester"], unique = true)])
data class TermEntity(
    @PrimaryKey val id: String,
    val school: String = "sdwu",
    val accountDigest: String,
    val year: String,
    val semester: String,
    val firstMonday: String,
    val weekCount: Int,
    val checkedAt: Long,
    val revision: Long = 1,
)

@Entity(tableName = "periods", primaryKeys = ["termId", "number"], foreignKeys = [ForeignKey(
    entity = TermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.CASCADE,
)])
data class PeriodEntity(val termId: String, val number: Int, val start: String, val end: String)

@Entity(tableName = "identities", indices = [Index("termId")], foreignKeys = [ForeignKey(
    entity = TermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.CASCADE,
)])
data class IdentityEntity(
    @PrimaryKey val id: String, val termId: String, val origin: String = "school", val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "0") val colorSlot: Int = 0,
)

data class MeetingFields(
    val name: String, val teacher: String, val room: String,
    val weekday: Int, val weeks: String, val periods: String,
    @ColumnInfo(defaultValue = "'periods'") val timeMode: String = "periods",
    val customStart: String? = null, val customEnd: String? = null,
)

@Entity(tableName = "school_baselines", foreignKeys = [ForeignKey(
    entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE,
)])
data class BaselineEntity(@PrimaryKey val id: String, @Embedded val fields: MeetingFields)

@Entity(tableName = "manual_arrangements", foreignKeys = [ForeignKey(
    entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE,
)])
data class ManualEntity(@PrimaryKey val id: String, @Embedded val fields: MeetingFields)

// 本地覆盖及单次例外与基线隔离；本轮首次导入不创建覆盖或例外。
@Entity(tableName = "local_overrides", foreignKeys = [ForeignKey(
    entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE,
)])
data class OverrideEntity(
    @PrimaryKey val id: String, val name: String? = null, val teacher: String? = null, val room: String? = null,
    val weekday: Int? = null, val weeks: String? = null, val timeMode: String? = null,
    val periods: String? = null, val customStart: String? = null, val customEnd: String? = null,
)

@Entity(tableName = "single_exceptions", primaryKeys = ["arrangementId", "originalDate"], foreignKeys = [ForeignKey(
    entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["arrangementId"], onDelete = ForeignKey.CASCADE,
)])
data class ExceptionEntity(
    val arrangementId: String, val originalDate: String, val kind: String, val targetDate: String? = null,
    val timeMode: String? = null, val periods: String? = null, val customStart: String? = null,
    val customEnd: String? = null, val room: String? = null,
)

@Entity(tableName = "projections", indices = [Index("termId")], foreignKeys = [
    ForeignKey(entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.CASCADE),
])
data class ProjectionEntity(@PrimaryKey val id: String, val termId: String, @Embedded val fields: MeetingFields)

@Entity(tableName = "unscheduled", indices = [Index("termId")], foreignKeys = [ForeignKey(
    entity = TermEntity::class, parentColumns = ["id"], childColumns = ["termId"], onDelete = ForeignKey.CASCADE,
)])
data class UnscheduledEntity(@PrimaryKey val id: String, val termId: String, val name: String)

@Entity(tableName = "source_acknowledgements", primaryKeys = ["arrangementId", "kind"], foreignKeys = [ForeignKey(
    entity = IdentityEntity::class, parentColumns = ["id"], childColumns = ["arrangementId"], onDelete = ForeignKey.CASCADE,
)])
data class AcknowledgementEntity(val arrangementId: String, val kind: String)

data class TermBundle(
    @Embedded val term: TermEntity,
    @Relation(parentColumn = "id", entityColumn = "termId") val periods: List<PeriodEntity>,
    @Relation(parentColumn = "id", entityColumn = "termId") val projections: List<ProjectionEntity>,
    @Relation(parentColumn = "id", entityColumn = "termId") val unscheduled: List<UnscheduledEntity>,
    @Relation(parentColumn = "id", entityColumn = "termId") val identities: List<IdentityEntity>,
    @Relation(parentColumn = "id", entityColumn = "arrangementId", associateBy = Junction(
        value = IdentityEntity::class, parentColumn = "termId", entityColumn = "id",
    )) val exceptions: List<ExceptionEntity>,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(
        value = IdentityEntity::class, parentColumn = "termId", entityColumn = "id",
    )) val baselines: List<BaselineEntity>,
)

@Dao
interface ScheduleDao {
    @Transaction @Query("SELECT * FROM terms ORDER BY firstMonday DESC")
    fun observe(): Flow<List<TermBundle>>
    @Query("SELECT * FROM terms") suspend fun terms(): List<TermEntity>
    @Query("SELECT b.* FROM school_baselines b INNER JOIN identities i ON i.id = b.id WHERE i.termId = :termId")
    suspend fun baselines(termId: String): List<BaselineEntity>
    @Query("SELECT * FROM unscheduled WHERE termId = :termId") suspend fun unscheduled(termId: String): List<UnscheduledEntity>
    @Query("SELECT * FROM periods WHERE termId = :termId ORDER BY number") suspend fun periods(termId: String): List<PeriodEntity>
    @Query("UPDATE terms SET checkedAt = :checkedAt WHERE id = :id") suspend fun checked(id: String, checkedAt: Long)
    @Query("UPDATE terms SET revision = revision + 1 WHERE id = :id") suspend fun edited(id: String)
    @Query("UPDATE terms SET school = 'sdwu', accountDigest = :accountDigest, checkedAt = :checkedAt, revision = revision + 1 WHERE id = :id")
    suspend fun bindSchool(id: String, accountDigest: String, checkedAt: Long)
    @Query("SELECT * FROM identities WHERE id = :id") suspend fun identity(id: String): IdentityEntity?
    @Query("SELECT * FROM projections WHERE termId = :termId") suspend fun projections(termId: String): List<ProjectionEntity>
    @Query("SELECT * FROM identities WHERE termId = :termId") suspend fun identities(termId: String): List<IdentityEntity>
    @Query("SELECT * FROM single_exceptions WHERE arrangementId = :id") suspend fun exceptions(id: String): List<ExceptionEntity>
    @Query("SELECT * FROM local_overrides WHERE id = :id") suspend fun overrides(id: String): OverrideEntity?
    @Query("UPDATE identities SET colorSlot = :color WHERE id = :id") suspend fun color(id: String, color: Int)
    @Upsert suspend fun putProjection(projection: ProjectionEntity)
    @Upsert suspend fun putOverride(value: OverrideEntity)
    @Upsert suspend fun putManual(value: ManualEntity)
    @Query("SELECT * FROM manual_arrangements WHERE id = :id") suspend fun manual(id: String): ManualEntity?
    @Upsert suspend fun putException(value: ExceptionEntity)
    @Query("DELETE FROM single_exceptions WHERE arrangementId = :id AND originalDate = :date") suspend fun clearException(id: String, date: String)
    @Query("SELECT e.* FROM single_exceptions e INNER JOIN identities i ON e.arrangementId = i.id WHERE i.termId = :termId AND i.hidden = 0")
    suspend fun termExceptions(termId: String): List<ExceptionEntity>
    @Query("UPDATE identities SET hidden = :hidden WHERE id = :id") suspend fun hide(id: String, hidden: Boolean)
    @Query("DELETE FROM identities WHERE id = :id") suspend fun deleteIdentity(id: String)
    @Insert suspend fun insertTerm(term: TermEntity)
    @Insert suspend fun insertPeriods(periods: List<PeriodEntity>)
    @Insert suspend fun insertIdentities(identities: List<IdentityEntity>)
    @Insert suspend fun insertBaselines(baselines: List<BaselineEntity>)
    @Insert suspend fun insertProjections(projections: List<ProjectionEntity>)
    @Insert suspend fun insertUnscheduled(courses: List<UnscheduledEntity>)
    @Insert suspend fun insertAcknowledgements(acknowledgements: List<AcknowledgementEntity>)
}

@Database(entities = [TermEntity::class, PeriodEntity::class, IdentityEntity::class, BaselineEntity::class,
    OverrideEntity::class, ExceptionEntity::class, ProjectionEntity::class, UnscheduledEntity::class,
    AcknowledgementEntity::class, ManualEntity::class], version = 2, exportSchema = true)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao
}
