package ru.blockscan.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import ru.blockscan.app.core.SseParser
import ru.blockscan.app.domain.*

object Res {
    fun text(name: String): String =
        Res::class.java.classLoader!!.getResource(name)!!.readText(Charsets.UTF_8)
}

object Fixtures {
    /** Реальный поток SSE для 1.1.1.1 (снят с cheburcheck.ru): started, 37 result, done. */
    fun probeEvents(): List<ProbeEvent> {
        val parser = SseParser()
        val out = ArrayList<ProbeEvent>()
        for (line in Res.text("probe_1.1.1.1.sse").lines()) {
            parser.feed(line)?.let { ProbeParser.mapEvent(it.event, it.data)?.let(out::add) }
        }
        parser.flush()?.let { ProbeParser.mapEvent(it.event, it.data)?.let(out::add) }
        return out
    }

    fun probeFlow(perEventDelayMs: Long = 0): Flow<ProbeEvent> = flow {
        for (e in probeEvents()) {
            if (perEventDelayMs > 0) kotlinx.coroutines.delay(perEventDelayMs)
            emit(e)
        }
    }

    fun smallProbeFlow(delayMs: Long = 100): Flow<ProbeEvent> = flow {
        emit(ProbeEvent.Started("j", 1, null, "{}"))
        kotlinx.coroutines.delay(delayMs)
        val raw = """{"asn":"AS1","cdn_unblocked":false,"dns":null,"dpi_hop":1,"host_results":[],"job_id":"j","probe_id":"1","provider":"P","region":"R","target_hop":2,"verdicts":["ok"]}"""
        emit(ProbeEvent.Result(ProbeParser.parseResult(raw), raw))
        emit(ProbeEvent.Done(1, 1, "{}"))
    }

    fun checkJson(target: String, id: String? = "id-$target", listed: Boolean = false): String =
        """{${if (id != null) "\"id\":\"$id\"," else ""}"target":"$target","target_type":"IPv4-адрес","blocked":$listed,"ips":["$target"],"blocked_subnets":[],"cdn_providers":{},"geo":{"asn":"AS1","country_code":null,"organisation":"Org","city_geo_name_id":null,"location":"-"},"reverse_lookup":[],"complaints":[]}"""
}

class FakeApi(
    var onCheck: suspend (String) -> String = { Fixtures.checkJson(it) },
    var onProbe: (String) -> Flow<ProbeEvent> = { Fixtures.smallProbeFlow() },
) : CheckApi {
    val checkTargets = ArrayList<String>()
    val probeIds = ArrayList<String>()

    override suspend fun status(): ServiceStatus = ProbeParser.parseStatus(Res.text("status.json"))
    override suspend fun healthcheck(): Boolean = true
    override suspend fun check(target: String): String {
        checkTargets += target
        return onCheck(target)
    }

    override fun probe(id: String): Flow<ProbeEvent> {
        probeIds += id
        return onProbe(id)
    }
}

/** Хранилище в памяти с той же семантикой, что у SqliteStore. */
class MemStore : ScanStore, HistoryStore {
    override val changes: SharedFlow<Unit> =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    class Item(val seq: Int, var state: ItemState, var overall: Overall?, var record: CheckRecord?, var updated: Long = 0)

    val jobs = LinkedHashMap<Long, JobRow>()
    val items = HashMap<Long, LinkedHashMap<String, Item>>()
    val history = ArrayList<HistoryEntry>()
    private var nextJob = 1L
    private var nextHist = 1L

    override fun createJob(input: String, rangeDesc: String, targets: Sequence<String>, total: Int): Long {
        val id = nextJob++
        jobs[id] = JobRow(id, 0, input, rangeDesc, total, JobState.RUNNING, null)
        val m = LinkedHashMap<String, Item>()
        var seq = 0
        for (t in targets) if (t !in m) m[t] = Item(seq++, ItemState.QUEUED, null, null)
        items[id] = m
        return id
    }

    override fun getJob(id: Long): JobRow? = jobs[id]
    override fun latestUnfinishedJob(): JobRow? =
        jobs.values.filter { it.state == JobState.RUNNING || it.state == JobState.PAUSED }.maxByOrNull { it.id }

    override fun setJobState(id: Long, state: JobState, finishedAt: Long?) {
        jobs[id]?.let { jobs[id] = it.copy(state = state, finishedAt = finishedAt ?: it.finishedAt) }
    }

    override fun resetRunning(jobId: Long) {
        items[jobId]?.values?.forEach { if (it.state == ItemState.RUNNING) it.state = ItemState.QUEUED }
    }

    override fun claimNext(jobId: Long): String? {
        val e = items[jobId]?.entries?.firstOrNull { it.value.state == ItemState.QUEUED } ?: return null
        e.value.state = ItemState.RUNNING
        return e.key
    }

    override fun saveItem(jobId: Long, target: String, state: ItemState, record: CheckRecord) {
        val it = items[jobId]?.get(target) ?: return
        it.state = state
        it.record = record
        it.overall = if (state == ItemState.FINISHED) record.overall() else null
    }

    override fun counts(jobId: Long): JobCounts {
        var total = 0; var queued = 0; var running = 0; var finished = 0
        var blocked = 0; var notBlocked = 0; var partial = 0; var pending = 0; var errors = 0; var unknown = 0
        for (i in items[jobId]?.values.orEmpty()) {
            total++
            when (i.state) {
                ItemState.QUEUED -> queued++
                ItemState.RUNNING -> running++
                ItemState.FINISHED -> {
                    finished++
                    when (i.overall) {
                        Overall.BLOCKED -> blocked++
                        Overall.NOT_LISTED, Overall.COMPLETE -> notBlocked++
                        Overall.DYNAMIC_PARTIAL -> partial++
                        Overall.DYNAMIC_PENDING -> pending++
                        Overall.ERROR -> errors++
                        else -> unknown++
                    }
                }
            }
        }
        return JobCounts(total, queued, running, finished, blocked, notBlocked, partial, pending, errors, unknown)
    }

    override fun queryItems(jobId: Long, q: ItemQuery): List<ItemRow> =
        items[jobId]?.entries.orEmpty()
            .filter { q.search.isBlank() || it.key.contains(q.search.trim()) }
            .filter {
                val o = it.value.overall
                when (q.filter) {
                    ItemFilter.ALL -> true
                    ItemFilter.BLOCKED -> o == Overall.BLOCKED
                    ItemFilter.NOT_BLOCKED -> o == Overall.NOT_LISTED || o == Overall.COMPLETE
                    ItemFilter.PENDING -> it.value.state != ItemState.FINISHED || o == Overall.DYNAMIC_PENDING
                    ItemFilter.PARTIAL -> o == Overall.DYNAMIC_PARTIAL
                    ItemFilter.ERRORS -> o == Overall.ERROR
                    ItemFilter.UNKNOWN -> o == Overall.UNKNOWN
                }
            }
            .sortedBy { it.value.seq }
            .drop(q.offset).take(q.limit)
            .map { ItemRow(it.key, it.value.state, it.value.overall, it.value.record, it.value.updated) }

    override fun getItem(jobId: Long, target: String): ItemRow? =
        items[jobId]?.get(target)?.let { ItemRow(target, it.state, it.overall, it.record, it.updated) }

    // ---- history ----
    override fun addSingle(rec: CheckRecord) {
        history += HistoryEntry(nextHist++, rec.startedAt, HistoryKind.SINGLE, rec.target, rec.overall(), rec.overall().ru, null, rec.completionLabel(), null, null)
    }

    override fun addScan(jobId: Long, input: String, rangeDesc: String, ts: Long) {
        history += HistoryEntry(nextHist++, ts, HistoryKind.SCAN, input, null, rangeDesc, null, "В процессе", jobId, null)
    }

    override fun updateScan(jobId: Long, completion: String, summary: String, durationMs: Long) {
        val i = history.indexOfFirst { it.jobId == jobId }
        if (i >= 0) history[i] = history[i].copy(completion = completion, summary = summary, durationMs = durationMs)
    }

    override fun list(limit: Int): List<HistoryEntry> = history.reversed().take(limit)
    override fun get(id: Long): HistoryEntry? = history.firstOrNull { it.id == id }
    override fun last(): HistoryEntry? = history.lastOrNull()
    override fun stats(): HistoryStats = HistoryStats(history.size.toLong(), history.count { it.overall == Overall.BLOCKED }.toLong())
    override fun clear() { history.clear() }
}

fun rec(
    dyn: DynamicStatus,
    probes: List<String> = emptyList(),
    staticRaw: String? = Res.text("check_1.1.1.1.json"),
    staticError: String? = null,
    online: Int? = null,
) = CheckRecord(
    target = "1.1.1.1", startedAt = 0, staticRaw = staticRaw, staticAt = 1, staticError = staticError,
    dyn = dyn, probeRaw = probes, online = online, finishedAt = 2,
)

fun fixtureResultRaws(): List<String> =
    Fixtures.probeEvents().filterIsInstance<ProbeEvent.Result>().map { it.raw }
