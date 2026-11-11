package com.drishtisaathi.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.SessionResult
import com.drishtisaathi.engine.StaffMeasurements
import com.drishtisaathi.engine.vision.DeviceFit
import com.drishtisaathi.engine.vision.PairedEye
import com.drishtisaathi.engine.vision.TrialLensText
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * One customer session. Measurements and answers only: no name, no phone number, no images (Section 6).
 * Structured data is stored as JSON so the engine model can evolve without a migration per field.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val lang: String,
    /** Device model, for the per-phone-model correction in the learning loop. Not personal data. */
    val phoneModel: String,
    val answersJson: String,
    val staffJson: String,
    val resultJson: String,
    val referralLevel: String,
    /** When the customer tapped "I agree" on the consent screen (addition 1). */
    val consentAt: Long? = null,
    // ---- Staff verification (paired record) ----
    val finalTrialRight: String? = null,
    val finalTrialLeft: String? = null,
    val finalAdd: String? = null,
    val pdMeterMm: Double? = null,
    val verifiedAt: Long? = null,
    /** v3: camera face measurement + screen test results ([Measurements] as JSON); null for older sessions. */
    val measurementsJson: String? = null,
)

/** Camera capture (M2) and on-screen test (M3) results stored with a session. Numbers only, never images. */
@kotlinx.serialization.Serializable
data class Measurements(
    val face: com.drishtisaathi.engine.MeasuredFace? = null,
    val tests: com.drishtisaathi.engine.VisionTests? = null,
    /** Staff demo mode: invented sample customer, labelled DEMO on every screen and PDF. */
    val demo: Boolean = false,
)

/**
 * Learning loop: this phone model's fitted correction (final trial lens SE = intercept + slope × measured SE), refitted
 * from verified sessions. Numbers only. One row per phone model; removed again when fewer than the minimum remain.
 */
@Entity(tableName = "device_corrections")
data class DeviceCorrectionEntity(
    @PrimaryKey val phoneModel: String,
    val intercept: Double,
    val slope: Double,
    val sessions: Int,
    val eyes: Int,
    val residualSdD: Double,
    val maxCorrectionD: Double,
    val fittedAt: Long,
) {
    fun toFit() = DeviceFit(intercept, slope, sessions, eyes, residualSdD, maxCorrectionD)

    companion object {
        fun from(phoneModel: String, f: DeviceFit, at: Long) =
            DeviceCorrectionEntity(phoneModel, f.intercept, f.slope, f.sessions, f.eyes, f.residualSdD, f.maxCorrectionD, at)
    }
}

@Dao
interface DeviceCorrectionDao {
    @Query("SELECT * FROM device_corrections WHERE phoneModel = :model") suspend fun get(model: String): DeviceCorrectionEntity?
    @Upsert suspend fun upsert(e: DeviceCorrectionEntity)
    @Query("DELETE FROM device_corrections WHERE phoneModel = :model") suspend fun delete(model: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE phoneModel = :model AND verifiedAt IS NOT NULL")
    suspend fun verifiedFor(model: String): List<SessionEntity>
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [SessionEntity::class, DeviceCorrectionEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun deviceCorrections(): DeviceCorrectionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds consentAt. Keeps sessions saved by earlier test builds. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN consentAt INTEGER")
            }
        }
        /** v3 adds measurementsJson (camera + screen tests). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN measurementsJson TEXT")
            }
        }
        /** v4 adds device_corrections (learning-loop fit per phone model). SQL matches schemas/.../4.json. */
        const val CREATE_DEVICE_CORRECTIONS =
            "CREATE TABLE IF NOT EXISTS `device_corrections` (`phoneModel` TEXT NOT NULL, `intercept` REAL NOT NULL, " +
                "`slope` REAL NOT NULL, `sessions` INTEGER NOT NULL, `eyes` INTEGER NOT NULL, `residualSdD` REAL NOT NULL, " +
                "`maxCorrectionD` REAL NOT NULL, `fittedAt` INTEGER NOT NULL, PRIMARY KEY(`phoneModel`))"
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(CREATE_DEVICE_CORRECTIONS)
        }
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drishti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}

/** Typed decode/encode around [SessionEntity]. */
object SessionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun answers(e: SessionEntity): InterviewAnswers = json.decodeFromString(InterviewAnswers.serializer(), e.answersJson)
    fun staff(e: SessionEntity): StaffMeasurements = json.decodeFromString(StaffMeasurements.serializer(), e.staffJson)
    fun result(e: SessionEntity): SessionResult = json.decodeFromString(SessionResult.serializer(), e.resultJson)

    fun encode(a: InterviewAnswers) = json.encodeToString(InterviewAnswers.serializer(), a)
    fun encode(s: StaffMeasurements) = json.encodeToString(StaffMeasurements.serializer(), s)
    fun encode(r: SessionResult) = json.encodeToString(SessionResult.serializer(), r)
    fun measurements(e: SessionEntity): Measurements =
        e.measurementsJson?.let { json.decodeFromString(Measurements.serializer(), it) } ?: Measurements()
    fun encode(m: Measurements) = json.encodeToString(Measurements.serializer(), m)
}


/** Learning loop: paired eyes (raw measured far-point SE vs final trial lens SE) from stored sessions. */
object PairedRecords {
    /**
     * Uses verified, non-demo sessions with screen-test results. Per eye: a measured SE (not "no myopia at arm's
     * length") and a readable final trial lens. Rows from other phone models must already be filtered out.
     */
    fun from(rows: List<SessionEntity>): List<PairedEye> = rows.flatMap { e ->
        if (e.verifiedAt == null) return@flatMap emptyList()
        val m = runCatching { SessionCodec.measurements(e) }.getOrNull() ?: return@flatMap emptyList()
        if (m.demo) return@flatMap emptyList()
        val tests = m.tests ?: return@flatMap emptyList()
        listOfNotNull(
            pair(e.id, tests.right, e.finalTrialRight),
            pair(e.id, tests.left, e.finalTrialLeft),
        )
    }

    private fun pair(id: Long, eye: com.drishtisaathi.engine.MeasuredEye?, finalText: String?): PairedEye? {
        val measured = eye?.takeIf { !it.noMyopiaAtArmsLength }?.seD ?: return null
        val final = TrialLensText.sphericalEquivalent(finalText) ?: return null
        return PairedEye(id, measured, final)
    }
}
