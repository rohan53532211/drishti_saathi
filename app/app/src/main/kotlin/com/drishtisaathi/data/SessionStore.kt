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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.drishtisaathi.engine.InterviewAnswers
import com.drishtisaathi.engine.SessionResult
import com.drishtisaathi.engine.StaffMeasurements
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

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [SessionEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

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
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drishti.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
