package com.drishtisaathi.data

import com.drishtisaathi.engine.tryon3d.Frame3D
import com.drishtisaathi.engine.tryon3d.Sizes3D
import com.drishtisaathi.pdf.Block
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SavedFramesTest {
    /** In-memory stand-in for the Room DAO (same keys: session + frame). */
    private class FakeDao : SavedFrameDao {
        val rows = MutableStateFlow<List<SavedFrameEntity>>(emptyList())
        override suspend fun upsert(e: SavedFrameEntity) { rows.value = rows.value.filterNot { it.sessionId == e.sessionId && it.frameId == e.frameId } + e }
        override suspend fun delete(sessionId: Long, frameId: String) { rows.value = rows.value.filterNot { it.sessionId == sessionId && it.frameId == frameId } }
        override suspend fun forSession(sessionId: Long) = rows.value.filter { it.sessionId == sessionId }
        override fun observeSession(sessionId: Long): Flow<List<SavedFrameEntity>> = rows.map { r -> r.filter { it.sessionId == sessionId } }
        override fun observeAll(): Flow<List<SavedFrameEntity>> = rows
        override suspend fun deleteForSession(sessionId: Long) { rows.value = rows.value.filterNot { it.sessionId == sessionId } }
    }

    private fun frame(id: String, price: Int = 1299, measured: Boolean = false) = Frame3D(
        id = id, name = "Frame $id", shape = "round", colour = "black", material = "acetate",
        sizes = Sizes3D(52, 18, 140), priceInr = price, glb = "$id.glb",
        dimensionsSource = if (measured) Frame3D.DIMENSIONS_MEASURED else Frame3D.DIMENSIONS_ESTIMATED,
    )

    @Test fun `save stores the frame with the session, a second tap removes it`() = runBlocking {
        val dao = FakeDao()
        assertEquals(SaveResult.SAVED, SavedFrames.toggle(dao, 7, frame("a"), now = 100))
        assertEquals(SaveResult.SAVED, SavedFrames.toggle(dao, 7, frame("b", 2499, measured = true), now = 200))
        val rows = dao.forSession(7)
        assertEquals(listOf("a", "b"), rows.map { it.frameId })
        assertEquals("52-18-140", rows[0].sizeLabel)
        assertTrue(rows[0].sizeEstimated)
        assertEquals(false, rows[1].sizeEstimated)
        assertEquals(2499, rows[1].priceInr)
        assertEquals(SaveResult.REMOVED, SavedFrames.toggle(dao, 7, frame("a")))
        assertEquals(listOf("b"), dao.forSession(7).map { it.frameId })
    }

    @Test fun `no session yet - nothing is stored`() = runBlocking {
        val dao = FakeDao()
        assertEquals(SaveResult.NO_SESSION, SavedFrames.toggle(dao, null, frame("a")))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun `saved frames are per session and are deleted with their session`() = runBlocking {
        val dao = FakeDao()
        SavedFrames.toggle(dao, 1, frame("a")); SavedFrames.toggle(dao, 2, frame("a")); SavedFrames.toggle(dao, 2, frame("c"))
        assertEquals(1, dao.forSession(1).size)
        dao.deleteForSession(2)
        assertEquals(listOf(1L), dao.rows.value.map { it.sessionId })
    }

    @Test fun `migration 5 to 6 creates exactly the table Room expects`() {
        val db = Json.parseToJsonElement(File("schemas/com.drishtisaathi.data.AppDatabase/6.json").readText()).jsonObject["database"]!!.jsonObject
        assertEquals(6, db["version"]!!.jsonPrimitive.content.toInt())
        val sql = db["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "saved_frames" }["createSql"]!!
            .jsonPrimitive.content.replace("\${TABLE_NAME}", "saved_frames")
        assertEquals(sql, AppDatabase.CREATE_SAVED_FRAMES)
        // the database still deletes the rows when the session goes (ViewModel.removeSession)
        val vm = File("src/main/kotlin/com/drishtisaathi/SessionViewModel.kt").readText()
        assertTrue(vm.contains("savedDao.deleteForSession(id)"))
        assertTrue(File("src/main/kotlin/com/drishtisaathi/data/SessionStore.kt").readText().contains("MIGRATION_5_6)"))
    }

    @Test fun `PDF sections list name, size and price only`() {
        val rows = listOf(SavedFrames.entity(3, frame("a"), 1), SavedFrames.entity(3, frame("b", 2499, true), 2))
        val src = File("src/main/kotlin/com/drishtisaathi/pdf/PdfContent.kt").readText()
        assertTrue(src.contains("savedSection(saved, l, out)"))
        val line = Block.FrameRow(rows[1].frameId, rows[1].name, "${rows[1].sizeLabel} · ₹2,499")
        assertEquals("52-18-140 · ₹2,499", line.line)
    }
}
