package pt.gicristovao.voavoa

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Resumo em português de um NOTAM (formato ICAO). Campos não reconhecidos ficam a null. */
data class NotamSummary(
    val what: String?,
    val where: String?,
    val start: Instant?,
    val end: Instant?,
    val schedule: String?,
    val altitude: String?,
    val exceptions: String?,
)

object Notam {

    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val shortFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(lisbon)
    private val longFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(lisbon)

    fun parse(raw: String): NotamSummary {
        val t = raw.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()

        fun field(letter: Char): String? =
            Regex("""(?:^|\s)$letter\)\s*(.*?)(?=\s[A-GQ]\)\s|$)""").find(t)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        val e = field('E') ?: ""
        return NotamSummary(
            what = what(e),
            where = where(e),
            start = time(field('B')),
            end = time(field('C')),
            schedule = schedule(field('D')),
            altitude = altitude(field('F'), field('G')),
            exceptions = exceptions(e),
        )
    }

    private fun time(s: String?): Instant? {
        val d = s?.let { Regex("""\d{10}""").find(it)?.value } ?: return null
        return runCatching {
            LocalDateTime.of(
                2000 + d.substring(0, 2).toInt(), d.substring(2, 4).toInt(), d.substring(4, 6).toInt(),
                d.substring(6, 8).toInt(), d.substring(8, 10).toInt()
            ).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    private fun schedule(d: String?): String? {
        if (d == null) return null
        val u = d.uppercase()
        return when {
            u.replace(" ", "").contains("SR-SS") -> "Só de dia (do nascer ao pôr do sol)"
            u == "H24" -> null
            else -> "Horário (UTC): $d"
        }
    }

    private fun what(e: String): String? {
        val u = e.uppercase()
        return when {
            u.contains("NO CIVILIAN UAS") ->
                "voo de drones civis proibido" +
                    if (u.contains("OPEN AND SPECIFIC")) " (categorias aberta e específica)" else ""
            u.contains("NO FLY ZONE") || u.contains("NO FLIGHTS ALLOWED") ->
                if (u.contains("AIRSPACE RESERVATION")) "reserva de espaço aéreo, voo proibido" else "voo proibido"
            u.contains("AIRSPACE RESERVATION") -> "reserva de espaço aéreo"
            else -> null
        }
    }

    private fun where(e: String): String? {
        val place = Regex("""\(([^)]+)\)""").findAll(e).map { it.groupValues[1].trim() }
            .filter { !it.uppercase().contains("NO FLY ZONE") }
            .lastOrNull() ?: return null
        // Os NOTAM vêm sem acentos: repõe as terminações mais comuns (PORTIMAO -> Portimão).
        val name = place.lowercase(Locale("pt")).split(" ").joinToString(" ") { raw ->
            val w = when {
                raw.endsWith("cao") -> raw.dropLast(3) + "ção"
                raw.endsWith("ao") && raw.length > 2 -> raw.dropLast(2) + "ão"
                raw == "sao" -> "são"
                else -> raw
            }
            if (w.length <= 2 && w != "-") w else w.replaceFirstChar { it.titlecase(Locale("pt")) }
        }
        val u = e.uppercase()
        val radius = Regex("""WI\s+([\d.]+)\s*(KM|NM|M)\s+RADIUS""").find(u)
        val extra = when {
            u.contains("BOUNDED BY") -> " (área delimitada no mapa)"
            radius != null -> {
                val (v, unit) = radius.destructured
                " (raio de $v " + when (unit) { "KM" -> "km"; "NM" -> "milhas náuticas"; else -> "m" } + ")"
            }
            else -> ""
        }
        return name + extra
    }

    private fun level(s: String?): Pair<String, Boolean>? {
        if (s == null) return null
        val u = s.uppercase().trim()
        if (u == "SFC" || u == "GND") return "o solo" to true
        Regex("""(\d+)\s*FT\s*(AMSL|AGL|MSL)?""").find(u)?.let {
            val ft = it.groupValues[1].toInt()
            val m = (ft * 0.3048).roundToInt()
            val ref = when (it.groupValues[2]) {
                "AGL" -> " acima do solo"
                "AMSL", "MSL" -> " acima do nível do mar"
                else -> ""
            }
            return "$m m ($ft ft)$ref" to false
        }
        Regex("""FL\s*(\d+)""").find(u)?.let { return "FL${it.groupValues[1]}" to false }
        return s to false
    }

    private fun altitude(f: String?, g: String?): String? {
        val top = level(g) ?: return null
        val bottom = level(f)
        return if (bottom == null || bottom.second) "Do solo até ${top.first}"
        else "De ${bottom.first} até ${top.first}"
    }

    private fun exceptions(e: String): String? {
        val m = Regex("""\bEXC(?:EPT)?\b\s*(.*?)(?:\.|$)""").find(e.uppercase()) ?: return null
        var s = m.groupValues[1].trim()
        if (s.isBlank()) return null
        listOf(
            "STATE AIRCRAFT" to "aeronaves do Estado",
            "POLICE UAS OR RPAS" to "drones da polícia",
            "UAS OR RPAS" to "drones",
            "MEDEVAC" to "evacuação médica",
            "ANEPC" to "ANEPC (Proteção Civil)",
            "MILITARY" to "militares",
            "POLICE" to "polícia",
            "STATE" to "Estado",
            " AND " to " e ",
        ).forEach { (a, b) -> s = s.replace(a, b) }
        return s
    }

    fun period(start: Instant?, end: Instant?): String? {
        if (start == null && end == null) return null
        val year = Instant.now().atZone(lisbon).year
        fun fmt(i: Instant) = if (i.atZone(lisbon).year == year) shortFmt.format(i) else longFmt.format(i)
        return when {
            start != null && end != null -> "${fmt(start)} até ${fmt(end)}"
            start != null -> "A partir de ${fmt(start)}"
            else -> "Até ${fmt(end!!)}"
        }
    }

    fun relative(now: Instant, start: Instant?, end: Instant?): String? = when {
        start != null && now.isBefore(start) -> "Começa daqui a ${duration(Duration.between(now, start))}"
        end != null && now.isBefore(end) -> "Termina daqui a ${duration(Duration.between(now, end))}"
        end != null -> "Terminou"
        else -> null
    }

    private fun duration(d: Duration): String {
        val days = d.toDays()
        val hours = d.toHours()
        return when {
            days >= 1 -> if (days == 1L) "1 dia" else "$days dias"
            hours >= 1 -> if (hours == 1L) "1 hora" else "$hours horas"
            else -> "menos de 1 hora"
        }
    }
}
