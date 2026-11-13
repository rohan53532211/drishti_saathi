package com.drishtisaathi.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.drishtisaathi.engine.tryon3d.Frame3D
import kotlinx.coroutines.flow.Flow

/**
 * A frame the customer saved ("Save" in the 3D viewer or on their 3D face), stored with their session and deleted
 * with it. Frame facts only (id, name, size, price): nothing about the customer.
 */
@Entity(tableName = "saved_frames", primaryKeys = ["sessionId", "frameId"])
data class SavedFrameEntity(
    val sessionId: Long,
    val frameId: String,
    val name: String,
    /** A-DBL-temple, e.g. 52-18-140. */
    val sizeLabel: String,
    /** True when the size was estimated from a photo (Kaggle demo frames), false for a measured shop frame. */
    val sizeEstimated: Boolean,
    val priceInr: Int,
    val category: String,
    val savedAt: Long,
)

@Dao
interface SavedFrameDao {
    @Upsert suspend fun upsert(e: SavedFrameEntity)
    @Query("DELETE FROM saved_frames WHERE sessionId = :sessionId AND frameId = :frameId") suspend fun delete(sessionId: Long, frameId: String)
    @Query("SELECT * FROM saved_frames WHERE sessionId = :sessionId ORDER BY savedAt") suspend fun forSession(sessionId: Long): List<SavedFrameEntity>
    @Query("SELECT * FROM saved_frames WHERE sessionId = :sessionId ORDER BY savedAt") fun observeSession(sessionId: Long): Flow<List<SavedFrameEntity>>
    @Query("SELECT * FROM saved_frames ORDER BY sessionId DESC, savedAt") fun observeAll(): Flow<List<SavedFrameEntity>>
    @Query("DELETE FROM saved_frames WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: Long)
}

/** What a tap on Save did (for the toast). */
enum class SaveResult { SAVED, REMOVED, NO_SESSION }

object SavedFrames {
    /** Save [frame] to the session, or remove it when it is already saved. No session yet → nothing stored. */
    suspend fun toggle(dao: SavedFrameDao, sessionId: Long?, frame: Frame3D, now: Long = System.currentTimeMillis()): SaveResult {
        if (sessionId == null) return SaveResult.NO_SESSION
        return if (dao.forSession(sessionId).any { it.frameId == frame.id }) {
            dao.delete(sessionId, frame.id)
            SaveResult.REMOVED
        } else {
            dao.upsert(entity(sessionId, frame, now))
            SaveResult.SAVED
        }
    }

    fun entity(sessionId: Long, f: Frame3D, now: Long) = SavedFrameEntity(
        sessionId = sessionId, frameId = f.id, name = f.name, sizeLabel = f.sizes.label, sizeEstimated = f.sizesEstimated,
        priceInr = f.priceInr, category = f.category, savedAt = now,
    )
}
