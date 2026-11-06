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
    // ---- Staff verification (paired record) ----
    val finalTrialRight: String? = null,
    val finalTrialLeft: String? = null,
    val finalAdd: String? = null,
    val pdMeterMm: Double? = null,
    val verifiedAt: Long? = null,
)

@Dao
interface SessionDao {
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun observeAll(): Flow<List<SessionEntity>>
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
}

@Database(entities = [SessionEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drishti.db")
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
}
