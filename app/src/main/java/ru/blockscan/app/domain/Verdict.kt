package ru.blockscan.app.domain

/**
 * Порт логики сайта (frontend/src/lib/api/probe.ts: displayProbeVerdicts / selectProbeVerdict).
 */
object VerdictLogic {
    private val priority = listOf(
        Verdict.TSPU_BLOCK, Verdict.SNI_BLOCK, Verdict.DNS_SPOOFING, Verdict.WHITELIST,
        Verdict.CDN_BLOCK, Verdict.OK, Verdict.UNCERTAIN,
    )

    fun display(p: ProbeResult, isStaticCdn: Boolean): List<Verdict> {
        val mapped = p.verdicts.map { v ->
            when {
                v == Verdict.CDN_BLOCK -> if (isStaticCdn) Verdict.CDN_BLOCK else Verdict.OK
                isStaticCdn && p.cdnUnblocked != true && p.hostResultsSize != 0 && v == Verdict.OK -> Verdict.CDN_BLOCK
                else -> v
            }
        }
        return if (mapped.any { it.blocking }) mapped.filter { it != Verdict.OK } else mapped
    }

    fun top(p: ProbeResult, isStaticCdn: Boolean): Verdict? {
        val d = display(p, isStaticCdn)
        return priority.firstOrNull { it in d }
    }

    fun select(probes: List<ProbeResult>, isStaticCdn: Boolean): Verdict? {
        if (probes.isEmpty()) return null
        val votes = HashMap<Verdict, Int>()
        for (p in probes) top(p, isStaticCdn)?.let { votes[it] = (votes[it] ?: 0) + 1 }
        var winner: Verdict? = null
        var best = 0
        for (v in priority) {
            val c = votes[v] ?: 0
            if (c > best) { winner = v; best = c }
        }
        return if (winner == Verdict.UNCERTAIN) null else winner
    }
}

data class DynamicSummary(
    val winner: Verdict?,
    val counts: Map<Verdict, Int>,
    /** Сколько сканеров увидели блокировку (даже если большинство говорит «доступен»). */
    val blockingProbes: Int,
    val totalProbes: Int,
)

fun summarize(probes: List<ProbeResult>, isStaticCdn: Boolean): DynamicSummary {
    val counts = LinkedHashMap<Verdict, Int>()
    var blocking = 0
    for (p in probes) {
        val t = VerdictLogic.top(p, isStaticCdn) ?: continue
        counts[t] = (counts[t] ?: 0) + 1
        if (VerdictLogic.display(p, isStaticCdn).any { it.blocking }) blocking++
    }
    return DynamicSummary(VerdictLogic.select(probes, isStaticCdn), counts, blocking, probes.size)
}

fun CheckRecord.static(): StaticResult? = staticRaw?.let { runCatching { CheckParser.parse(it) }.getOrNull() }

fun CheckRecord.probes(): List<ProbeResult> =
    probeRaw.mapNotNull { runCatching { ProbeParser.parseResult(it) }.getOrNull() }

fun CheckRecord.summary(): DynamicSummary? {
    if (domainCheck) {
        val parts = addressResults.mapNotNull { it.summary() }
        if (parts.isEmpty()) return null
        val counts = LinkedHashMap<Verdict, Int>()
        parts.forEach { part -> part.counts.forEach { (verdict, count) -> counts[verdict] = (counts[verdict] ?: 0) + count } }
        val winner = Verdict.entries.firstOrNull { (counts[it] ?: 0) == counts.values.maxOrNull() }
            ?.takeUnless { it == Verdict.UNCERTAIN }
        return DynamicSummary(winner, counts, parts.sumOf { it.blockingProbes }, parts.sumOf { it.totalProbes })
    }
    val p = probes()
    if (p.isEmpty()) return null
    return summarize(p, static()?.isCdn == true)
}

fun CheckRecord.staticStatus(): StaticStatus {
    if (staticError != null) return StaticStatus.ERROR
    if (staticRaw == null) return StaticStatus.PENDING
    val s = static() ?: return StaticStatus.ERROR
    return when (s.listed) {
        true -> StaticStatus.LISTED
        false -> StaticStatus.NOT_LISTED
        null -> StaticStatus.UNKNOWN
    }
}

/**
 * Итоговое состояние. Отсутствие в списках никогда не превращается в «Проверка завершена»:
 * COMPLETE возможен только после события done от сканеров.
 */
fun CheckRecord.overall(): Overall {
    if (domainCheck) {
        if (!isTerminal) return Overall.DYNAMIC_PENDING
        if (domainResolutionError != null || addressResults.isEmpty()) return Overall.ERROR
        val outcomes = addressResults.map { it.overall() }.distinct()
        return outcomes.singleOrNull() ?: Overall.DYNAMIC_PARTIAL
    }
    val st = staticStatus()
    if (st == StaticStatus.ERROR) return Overall.ERROR
    if (st == StaticStatus.PENDING) return Overall.UNKNOWN
    return when (dyn) {
        DynamicStatus.DONE -> {
            val w = summary()?.winner
            when {
                w == null -> Overall.UNKNOWN
                w.blocking -> Overall.BLOCKED
                else -> Overall.COMPLETE
            }
        }
        DynamicStatus.RUNNING, DynamicStatus.PARTIAL ->
            if (probeRaw.isEmpty()) Overall.DYNAMIC_PENDING else Overall.DYNAMIC_PARTIAL
        DynamicStatus.QUEUED -> Overall.DYNAMIC_PENDING
        DynamicStatus.FAILED -> Overall.ERROR
        DynamicStatus.NOT_REQUESTED, DynamicStatus.UNAVAILABLE -> when (st) {
            StaticStatus.LISTED -> Overall.BLOCKED
            StaticStatus.NOT_LISTED -> Overall.NOT_LISTED
            else -> Overall.UNKNOWN
        }
    }
}

/** На чём основан итог: нужен, чтобы «Заблокирован» по спискам не выглядел как подтверждённая недоступность. */
fun CheckRecord.basis(): String = if (domainCheck) {
    "по результатам отдельных проверок IP-адресов домена"
} else when (dyn) {
    DynamicStatus.DONE -> "по ответам сканеров"
    DynamicStatus.PARTIAL, DynamicStatus.RUNNING -> "по спискам и неполным ответам сканеров"
    else -> "только по спискам, динамическая проверка не выполнена"
}

fun CheckRecord.completionLabel(): String = when {
    !isTerminal -> "Прервано"
    domainCheck && domainResolutionError != null -> "Ошибка DNS"
    domainCheck && dyn == DynamicStatus.PARTIAL -> "Частично"
    staticError != null -> "Ошибка"
    dyn == DynamicStatus.DONE -> "Завершено"
    dyn == DynamicStatus.PARTIAL -> "Частично"
    dyn == DynamicStatus.FAILED -> "Ошибка динамической проверки"
    dyn == DynamicStatus.UNAVAILABLE -> "Только статика"
    else -> "Завершено"
}
