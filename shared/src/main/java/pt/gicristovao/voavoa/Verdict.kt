package pt.gicristovao.voavoa

import java.time.Instant

/** Zonas num ponto, separadas em ativas, futuras e nacionais. */
data class PointCheck(val active: List<Zone>, val upcoming: List<Zone>, val national: List<Zone>) {
    val worst: ZoneType? get() = active.maxByOrNull { it.type.rank }?.type
}

object Verdict {

    fun check(zones: List<Zone>, p: LatLon, now: Instant = Instant.now()): PointCheck {
        val here = zones.filter { !it.isExpired(now) && it.contains(p) }
        return PointCheck(
            active = here.filter { it.isActive(now) && !it.isNationwide }.sortedByDescending { it.type.rank },
            upcoming = here.filter { it.isFuture(now) && !it.isNationwide }
                .sortedBy { z -> z.periods.mapNotNull { it.start }.minOrNull() },
            national = here.filter { it.isNationwide && it.isActive(now) },
        )
    }

    /** Frase curta para o relógio: limite de altura ou o que é preciso. */
    fun limit(c: PointCheck): String? = when (c.worst) {
        null -> "Regras gerais: até 120 m"
        ZoneType.PROHIBITED -> null
        ZoneType.REQ_AUTHORIZATION -> "Precisa de autorização"
        ZoneType.NO_RESTRICTION -> "Sem restrição"
        else -> {
            val m = c.active.firstNotNullOfOrNull { z ->
                z.message?.let { Regex("""(\d+)\s*m\s*SFC""", RegexOption.IGNORE_CASE).find(it) }
            }
            if (m != null) "Até ${m.groupValues[1]} m" else "Ver condições"
        }
    }
}
