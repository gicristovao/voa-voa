package pt.gicristovao.voavoa

import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Textos em português para mostrar ao utilizador. */
object Texts {

    private val lisbon: ZoneId = ZoneId.of("Europe/Lisbon")
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(lisbon)
    private val date = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(lisbon)

    fun dateTime(i: Instant): String = dateTime.format(i)
    fun date(i: Instant): String = date.format(i)

    fun type(t: ZoneType): String = when (t) {
        ZoneType.PROHIBITED -> "Proibida"
        ZoneType.REQ_AUTHORIZATION -> "Requer autorização"
        ZoneType.CONDITIONAL -> "Condicionada"
        ZoneType.NO_RESTRICTION -> "Sem restrição"
        ZoneType.UNKNOWN -> "Desconhecida"
    }

    fun verdict(t: ZoneType?): String = when (t) {
        null -> "Sem zonas geográficas UAS neste ponto"
        ZoneType.PROHIBITED -> "Voo proibido"
        ZoneType.REQ_AUTHORIZATION -> "Precisa de autorização"
        ZoneType.CONDITIONAL -> "Voo condicionado"
        ZoneType.NO_RESTRICTION -> "Zona assinalada, sem restrição"
        ZoneType.UNKNOWN -> "Zona com restrições"
    }

    fun verdictDetail(t: ZoneType?): String = when (t) {
        null -> "Não há zonas da ANAC aqui. Aplicam-se as regras gerais da categoria em que voas (na categoria aberta, até 120 m acima do solo)."
        ZoneType.PROHIBITED -> "Pelo menos uma zona proíbe o voo neste ponto. Vê as condições abaixo."
        ZoneType.REQ_AUTHORIZATION -> "É preciso pedir autorização antes de voar. Os contactos estão abaixo."
        ZoneType.CONDITIONAL -> "Podes voar, mas com as condições indicadas abaixo."
        ZoneType.NO_RESTRICTION -> "A zona serve apenas de informação. Lê a mensagem abaixo."
        ZoneType.UNKNOWN -> "Lê as condições abaixo."
    }

    fun reason(r: String): String = when (r.uppercase()) {
        "AIR_TRAFFIC" -> "Tráfego aéreo"
        "SENSITIVE" -> "Zona sensível"
        "PRIVACY" -> "Privacidade"
        "POPULATION" -> "População"
        "NATURE" -> "Natureza"
        "NOISE" -> "Ruído"
        "FOREIGN_TERRITORY" -> "Território estrangeiro"
        "EMERGENCY" -> "Emergência"
        else -> "Outro"
    }

    private fun refText(ref: String?): String = when (ref?.uppercase()) {
        "AGL", "SFC" -> "acima do solo"
        "AMSL" -> "acima do nível do mar"
        "WGS84" -> "(WGS84)"
        else -> ref ?: ""
    }

    private fun heightText(v: Double, uom: String?): String {
        val isFeet = uom?.lowercase() == "ft"
        return if (isFeet) "${v.roundToInt()} ft (${(v * 0.3048).roundToInt()} m)" else "${v.roundToInt()} m"
    }

    fun altitude(z: Zone): String? {
        val up = z.upper ?: return null
        val low = z.lower ?: 0.0
        val upper = "${heightText(up, z.uom)} ${refText(z.upperRef)}".trim()
        return if (low <= 0.0) "Do solo até $upper"
        else "De ${heightText(low, z.uom)} ${refText(z.lowerRef)} até $upper"
    }

    fun validity(z: Zone): String? {
        val p = z.periods.firstOrNull() ?: return null
        val s = p.start; val e = p.end
        return when {
            s != null && e != null -> "De ${dateTime(s)} a ${dateTime(e)}"
            e != null -> "Até ${dateTime(e)}"
            s != null -> "Desde ${dateTime(s)}"
            else -> null
        }
    }

    /** ED-318 'schedule': [{day:[..], startTime/endTime | startEvent/endEvent}] */
    fun schedule(arr: JSONArray): String? {
        val parts = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val days = o.optJSONArray("day").strings()
            val dayText = if (days.isEmpty() || days.any { it.equals("ANY", true) }) "Todos os dias"
            else days.joinToString(", ") { day(it) }
            val start = o.optStringOrNull("startTime")?.let { time(it) }
                ?: o.optStringOrNull("startEvent")?.let { event(it) }
            val end = o.optStringOrNull("endTime")?.let { time(it) }
                ?: o.optStringOrNull("endEvent")?.let { event(it) }
            parts.add(
                if (start != null && end != null) "$dayText, entre $start e $end" else dayText
            )
        }
        return parts.joinToString("\n").ifBlank { null }
    }

    private fun time(t: String): String = t.take(5) + if (t.endsWith("Z")) " UTC" else ""

    private fun event(e: String): String = when (e.uppercase()) {
        "BMCT" -> "o início do crepúsculo civil da manhã"
        "EECT" -> "o fim do crepúsculo civil da tarde"
        "SR" -> "o nascer do sol"
        "SS" -> "o pôr do sol"
        else -> e
    }

    private fun day(d: String): String = when (d.uppercase()) {
        "MON" -> "seg"; "TUE" -> "ter"; "WED" -> "qua"; "THU" -> "qui"
        "FRI" -> "sex"; "SAT" -> "sáb"; "SUN" -> "dom"
        "WEEKDAY" -> "dias úteis"; "WEEKEND" -> "fim de semana"; "HOL" -> "feriados"
        else -> d
    }

    /** "P60DT00H00M" -> "60 dias" */
    fun interval(iso: String?): String? {
        if (iso == null) return null
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?""").matchEntire(iso.trim()) ?: return null
        val d = m.groupValues[1].toIntOrNull() ?: 0
        val h = m.groupValues[2].toIntOrNull() ?: 0
        val parts = buildList {
            if (d > 0) add(if (d == 1) "1 dia" else "$d dias")
            if (h > 0) add(if (h == 1) "1 hora" else "$h horas")
        }
        return parts.joinToString(" e ").ifBlank { null }
    }

    fun phoneForDial(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (!digits.startsWith("+") && digits.startsWith("351") && digits.length == 12) "+$digits" else digits
    }
}
