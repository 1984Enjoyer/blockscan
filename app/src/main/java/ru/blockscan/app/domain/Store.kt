package ru.blockscan.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.Dispatchers

interface Observable {
    val changes: SharedFlow<Unit>
}

fun <T> Observable.observe(block: () -> T): Flow<T> =
    changes.onStart { emit(Unit) }.conflate().map { block() }.flowOn(Dispatchers.IO)

enum class JobState { RUNNING, PAUSED, STOPPED, FINISHED }
enum class ItemState { QUEUED, RUNNING, FINISHED }
enum class ItemFilter(val ru: String) {
    ALL("Все"), BLOCKED("Заблокированные"), NOT_BLOCKED("Не заблокированные"),
    PENDING("Ожидают"), PARTIAL("Частичные"), ERRORS("Ошибки"), UNKNOWN("Неизвестно"),
}
enum class ItemSort(val ru: String) { ORDER("По порядку"), STATUS("По статусу"), UPDATED("По времени") }

data class ItemQuery(
    val filter: ItemFilter = ItemFilter.ALL,
    val search: String = "",
    val sort: ItemSort = ItemSort.ORDER,
    val offset: Int = 0,
    val limit: Int = 500,
)

data class JobRow(
    val id: Long,
    val createdAt: Long,
    val input: String,
    val rangeDesc: String,
    val total: Int,
    val state: JobState,
    val finishedAt: Long?,
)

data class ItemRow(
    val target: String,
    val state: ItemState,
    val overall: Overall?,
    val record: CheckRecord?,
    val updatedAt: Long,
)

data class JobCounts(
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val finished: Int = 0,
    val blocked: Int = 0,
    val notBlocked: Int = 0,
    val partial: Int = 0,
    val pending: Int = 0,
    val errors: Int = 0,
    val unknown: Int = 0,
)

interface ScanStore : Observable {
    fun createJob(input: String, rangeDesc: String, targets: Sequence<String>, total: Int): Long
    fun getJob(id: Long): JobRow?
    fun latestUnfinishedJob(): JobRow?
    fun setJobState(id: Long, state: JobState, finishedAt: Long? = null)
    fun resetRunning(jobId: Long)
    /** Атомарно берёт следующий QUEUED-адрес и помечает его RUNNING. */
    fun claimNext(jobId: Long): String?
    fun saveItem(jobId: Long, target: String, state: ItemState, record: CheckRecord)
    fun counts(jobId: Long): JobCounts
    fun queryItems(jobId: Long, q: ItemQuery): List<ItemRow>
    fun getItem(jobId: Long, target: String): ItemRow?
}

enum class HistoryKind { SINGLE, SCAN }

data class HistoryEntry(
    val id: Long,
    val ts: Long,
    val kind: HistoryKind,
    val target: String,
    val overall: Overall?,
    val summary: String,
    val durationMs: Long?,
    val completion: String,
    val jobId: Long?,
    val recordJson: String?,
)

data class HistoryStats(val checked: Long, val blocked: Long)

interface HistoryStore : Observable {
    fun addSingle(rec: CheckRecord)
    fun addScan(jobId: Long, input: String, rangeDesc: String, ts: Long)
    fun updateScan(jobId: Long, completion: String, summary: String, durationMs: Long)
    fun list(limit: Int = 300): List<HistoryEntry>
    fun get(id: Long): HistoryEntry?
    fun last(): HistoryEntry?
    fun stats(): HistoryStats
    fun clear()
}
