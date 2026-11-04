package ru.blockscan.app.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.blockscan.app.domain.CheckRecord
import ru.blockscan.app.domain.ProbeResult
import ru.blockscan.app.domain.Verdict
import ru.blockscan.app.domain.VerdictLogic
import ru.blockscan.app.domain.probes
import ru.blockscan.app.domain.static
import java.util.Locale
import java.util.zip.GZIPInputStream

private const val MAP_WIDTH = 1000f
private const val MAP_HEIGHT = 330f
private val ACCESSIBLE = Color(0xFF36A872)
private val BLOCKED = Color(0xFFD64B4B)
private val MIXED = Color(0xFFD69A35)
private val NO_DATA = Color(0xFF536170)
private val SPECIAL = Color(0xFFB879D6)

private data class RegionShape(val name: String, val path: Path)
private data class RegionCount(val accessible: Int, val blocked: Int, val uncertain: Int) {
    val total get() = accessible + blocked + uncertain
    val color get() = when {
        accessible > blocked -> ACCESSIBLE
        blocked > accessible -> BLOCKED
        accessible > 0 -> MIXED
        else -> NO_DATA
    }
}
private data class SpecialCount(val label: String, val count: RegionCount)

private object RegionMapAsset {
    @Volatile private var cached: List<RegionShape>? = null

    fun load(context: Context): List<RegionShape> = cached ?: synchronized(this) {
        cached ?: context.assets.open("russia-regions.map.gz").use { compressed ->
            GZIPInputStream(compressed).bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .mapNotNull(::parseShape)
                .also { cached = it }
        }
    }

    private fun parseShape(line: String): RegionShape? {
        val columns = line.split('\t', limit = 2)
        if (columns.size != 2) return null
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            columns[1].split('|').forEach { ring ->
                val points = ring.split(';')
                if (points.size < 4) return@forEach
                points.forEachIndexed { index, point ->
                    val xy = point.split(',', limit = 2)
                    if (xy.size != 2) return@forEachIndexed
                    val x = xy[0].toFloatOrNull() ?: return@forEachIndexed
                    val y = xy[1].toFloatOrNull() ?: return@forEachIndexed
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
        return RegionShape(columns[0], path)
    }
}

@Composable
internal fun RegionAvailabilityMap(record: CheckRecord) {
    val context = LocalContext.current.applicationContext
    val shapes = produceState<List<RegionShape>>(emptyList(), context) {
        value = withContext(Dispatchers.IO) { RegionMapAsset.load(context) }
    }.value
    val isCdn = record.static()?.isCdn == true
    val specialAliases = mapOf(
        "донецкая народная республика" to "ДНР",
        "днр" to "ДНР",
        "луганская народная республика" to "ЛНР",
        "лнр" to "ЛНР",
    )
    val specialCounts = linkedMapOf<String, MutableList<ProbeResult>>()
    val byRegion = linkedMapOf<String, MutableList<ProbeResult>>()
    record.probes().forEach { probe ->
        val name = probe.region?.trim().orEmpty()
        if (name.isBlank()) return@forEach
        val special = specialAliases.entries.firstOrNull { normalizeRegion(name) == normalizeRegion(it.key) }?.value
        if (special != null) specialCounts.getOrPut(special) { mutableListOf() } += probe
        else byRegion.getOrPut(normalizeRegion(name)) { mutableListOf() } += probe
    }
    val aliases = mapOf(
        normalizeRegion("Москва") to normalizeRegion("Город Москва столица Российской Федерации город федерального значения"),
        normalizeRegion("Санкт-Петербург") to normalizeRegion("Город Санкт-Петербург город федерального значения"),
        normalizeRegion("Севастополь") to normalizeRegion("Город федерального значения Севастополь"),
        normalizeRegion("Крым") to normalizeRegion("Республика Крым"),
        normalizeRegion("Республика Башкортостан") to normalizeRegion("Республика Башкирия"),
        normalizeRegion("Республика Тыва") to normalizeRegion("Республика Тува"),
        normalizeRegion("Республика Северная Осетия — Алания") to normalizeRegion("Республика Северная Осетия"),
        normalizeRegion("Кемеровская область — Кузбасс") to normalizeRegion("Кемеровская область"),
        normalizeRegion("Ханты-Мансийский автономный округ — Югра") to normalizeRegion("Ханты-Мансийский АО"),
        normalizeRegion("Ямало-Ненецкий автономный округ") to normalizeRegion("Ямало-Ненецкий АО"),
    )
    val shapeKeys = shapes.associateBy { normalizeRegion(it.name) }
    val resolved = linkedMapOf<String, MutableList<ProbeResult>>()
    byRegion.forEach { (key, probes) ->
        val canonical = aliases[key] ?: key
        resolved.getOrPut(canonical) { mutableListOf() }.addAll(probes)
    }
    val counts = resolved.mapValues { (_, probes) -> countRegion(probes, isCdn) }
    val special = specialCounts.map { (label, probes) -> SpecialCount(label, countRegion(probes, isCdn)) }
    val matched = shapes.associateWith { shape -> counts[normalizeRegion(shape.name)] }
    val mappedCounts = matched.values.filterNotNull()
    val availableRegions = mappedCounts.count { it.accessible > it.blocked }
    val blockedRegions = mappedCounts.count { it.blocked > it.accessible }
    val mixedRegions = mappedCounts.count { it.accessible > 0 && it.accessible == it.blocked }
    val noDataRegions = shapes.size - mappedCounts.count { it.accessible + it.blocked > 0 }
    val specialMap = special.associateBy { it.label }

    SectionCard("Доступность по регионам") {
        Text(
            "Цвет региона определяется большинством уверенных ответов сканеров. Неясные ответы не учитываются в голосовании.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val mapHeight = maxWidth * (MAP_HEIGHT / MAP_WIDTH)
            Canvas(Modifier.fillMaxWidth().height(mapHeight)) {
                val sx = size.width / MAP_WIDTH
                val sy = size.height / MAP_HEIGHT
                withTransform({ scale(sx, sy, Offset.Zero) }) {
                    matched.forEach { (shape, state) ->
                        drawPath(shape.path, state?.color ?: NO_DATA, alpha = if (state == null) 0.55f else 1f)
                        drawPath(shape.path, Color(0xFF15212A), style = Stroke(width = 1.1f / sx.coerceAtLeast(0.1f)))
                    }
                    // ДНР/ЛНР в исходном наборе границ не представлены. Эти две метки показывают
                    // только примерное расположение, а не границы или принадлежность территории.
                    listOf(Offset(106f, 274f), Offset(115f, 265f)).forEach { point ->
                        drawCircle(SPECIAL.copy(alpha = 0.35f), radius = 10f, center = point)
                        drawCircle(SPECIAL, radius = 5f, center = point, style = Stroke(width = 2f))
                    }
                }
            }
            listOf("ДНР" to 0.75f, "ЛНР" to 0.69f).forEach { (name, y) ->
                val count = specialMap[name]?.count
                Surface(
                    modifier = Modifier.offset { IntOffset((maxWidth * 0.13f).roundToPx(), (mapHeight * y).roundToPx()) },
                    shape = RoundedCornerShape(6.dp),
                    color = (count?.color ?: SPECIAL).copy(alpha = 0.94f),
                    contentColor = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SPECIAL),
                ) {
                    Text(
                        text = if (count == null) name else "$name ${count.accessible}/${count.total}",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendDot(ACCESSIBLE, "Доступно $availableRegions")
            LegendDot(BLOCKED, "Блок $blockedRegions")
            LegendDot(MIXED, "Смешанно $mixedRegions")
            LegendDot(NO_DATA, "Нет данных $noDataRegions")
        }
        if (special.isNotEmpty()) {
            Text(
                "ДНР/ЛНР — отдельная отметка спорного статуса; они не включены в раскраску субъектов РФ. Метки показывают примерное расположение, не границы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "ДНР/ЛНР отмечены отдельно (примерное расположение); их границы не входят в набор карты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val unmatched = resolved.keys.filter { it !in shapeKeys.keys }
        if (unmatched.isNotEmpty()) {
            Text(
                "Нет границ карты для: ${unmatched.joinToString { key -> byRegion.keys.firstOrNull { normalizeRegion(it) == key } ?: key }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Границы: OpenStreetMap и GIS-Lab, обработка alt-dmitry/RussianMap.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

private fun countRegion(probes: List<ProbeResult>, isCdn: Boolean): RegionCount {
    var accessible = 0
    var blocked = 0
    var uncertain = 0
    probes.forEach { probe ->
        when (VerdictLogic.top(probe, isCdn)) {
            Verdict.TSPU_BLOCK, Verdict.SNI_BLOCK, Verdict.DNS_SPOOFING, Verdict.CDN_BLOCK -> blocked++
            Verdict.OK, Verdict.WHITELIST -> accessible++
            else -> uncertain++
        }
    }
    return RegionCount(accessible, blocked, uncertain)
}

private fun normalizeRegion(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace('ё', 'е')
    .filter(Char::isLetterOrDigit)
