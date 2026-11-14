package com.drishtisaathi.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the queue's session ids are kept between app starts. */
interface QueueStore {
    fun load(): Set<Long>
    fun save(ids: Set<Long>)
}

/** App-private preferences: session ids only, never report content (the report is rebuilt from the encrypted database). */
class PrefsQueueStore(context: Context) : QueueStore {
    private val prefs = context.getSharedPreferences("report_queue", Context.MODE_PRIVATE)
    override fun load(): Set<Long> = prefs.getStringSet("ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    override fun save(ids: Set<Long>) { prefs.edit().putStringSet("ids", ids.map { it.toString() }.toSet()).apply() }
}

/** What happened to one queued report. */
enum class SendResult {
    /** The server has it: leave the queue. */
    SENT,
    /** No server, no network, a server error: keep it and try again when the backend connects. */
    RETRY_LATER,
    /** The session no longer exists, or the server will never take it: leave the queue. */
    DROP,
}

/**
 * Phase A: the report of every check is ALWAYS sent to the optometrist. Each finished session is queued at once
 * (also when no server is set or the phone is offline) and sent as soon as the backend connects. The queue survives
 * app restarts. Oldest first; a failed send stops the pass (the server is not reachable) and everything stays queued.
 */
class ReportQueue(private val store: QueueStore) {
    private val lock = Mutex()
    private var ids: Set<Long> = store.load()
    private val _pending = MutableStateFlow(ids.size)
    /** Reports waiting to be sent ("Offline – queued N"). */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private fun set(next: Set<Long>) {
        ids = next
        store.save(next)
        _pending.value = next.size
    }

    @Synchronized fun enqueue(sessionId: Long) = set(ids + sessionId)
    @Synchronized fun remove(sessionId: Long) = set(ids - sessionId)
    @Synchronized fun snapshot(): List<Long> = ids.sorted()
    fun isQueued(sessionId: Long): Boolean = sessionId in snapshot()

    /**
     * Tries to send every queued report with [send]. Returns the number sent. Only one pass runs at a time; a
     * RETRY_LATER ends the pass so an unreachable server is not hammered.
     */
    suspend fun flush(send: suspend (Long) -> SendResult): Int = lock.withLock {
        var sent = 0
        for (id in snapshot()) {
            when (send(id)) {
                SendResult.SENT -> { remove(id); sent++ }
                SendResult.DROP -> remove(id)
                SendResult.RETRY_LATER -> return@withLock sent
            }
        }
        sent
    }
}
