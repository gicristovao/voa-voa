package pt.gicristovao.voavoa

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

class Poly(val outer: List<LatLon>, val holes: List<List<LatLon>>)

enum class ZoneType(val rank: Int) {
    NO_RESTRICTION(0), CONDITIONAL(1), REQ_AUTHORIZATION(2), PROHIBITED(3), UNKNOWN(1);

    companion object {
        fun of(s: String?): ZoneType = when (s?.uppercase()) {
            "PROHIBITED" -> PROHIBITED
            "REQ_AUTHORIZATION", "REQ_AUTHORISATION" -> REQ_AUTHORIZATION
            "CONDITIONAL" -> CONDITIONAL
            "NO_RESTRICTION" -> NO_RESTRICTION
            else -> UNKNOWN
        }
    }
}

data class Authority(
    val name: String?,
    val service: String?,
    val contactName: String?,
    val email: String?,
    val phone: String?,
    val site: String?,
    val purpose: String?,
    val intervalBefore: String?,
)

data class Period(val start: Instant?, val end: Instant?, val schedule: String?)

data class Zone(
    val id: String,
    val name: String,
    val type: ZoneType,
    val reasons: List<String>,
    val otherReason: String?,
    val message: String?,
    val lower: Double?,
    val upper: Double?,
    val lowerRef: String?,
    val upperRef: String?,
    val uom: String?,
    val polys: List<Poly>,
    val circleCenter: LatLon?,
    val circleRadius: Double?,
    val periods: List<Period>,
    val authorities: List<Authority>,
    val colorHex: String?,
    val notam: String?,
) {
    // Caixa envolvente, para descartar rapidamente zonas longe do ponto.
    val minLat: Double
    val maxLat: Double
    val minLon: Double
    val maxLon: Double

    init {
        var a = 90.0; var b = -90.0; var c = 180.0; var d = -180.0
        if (circleCenter != null && circleRadius != null) {
            val dLat = circleRadius / 111_320.0
            val dLon = circleRadius / (111_320.0 * cos(Math.toRadians(circleCenter.lat)))
            a = circleCenter.lat - dLat; b = circleCenter.lat + dLat
            c = circleCenter.lon - dLon; d = circleCenter.lon + dLon
        }
        for (p in polys) for (pt in p.outer) {
            a = min(a, pt.lat); b = max(b, pt.lat); c = min(c, pt.lon); d = max(d, pt.lon)
        }
        minLat = a; maxLat = b; minLon = c; maxLon = d
    }

    /** Zona informativa que cobre o país inteiro (ex.: "Condições nacionais"). Não se pinta no mapa. */
    val isNationwide: Boolean
        get() = type == ZoneType.NO_RESTRICTION && maxLat - minLat > 3.0

    fun isActive(now: Instant): Boolean =
        periods.isEmpty() || periods.any {
            (it.start == null || !now.isBefore(it.start)) && (it.end == null || now.isBefore(it.end))
        }

    fun isFuture(now: Instant): Boolean =
        !isActive(now) && periods.any { it.start != null && it.start.isAfter(now) }

    fun isExpired(now: Instant): Boolean = !isActive(now) && !isFuture(now)

    fun contains(p: LatLon): Boolean {
        if (p.lat < minLat || p.lat > maxLat || p.lon < minLon || p.lon > maxLon) return false
        if (circleCenter != null && circleRadius != null) {
            return distanceMeters(circleCenter, p) <= circleRadius
        }
        return polys.any { poly -> inRing(p, poly.outer) && poly.holes.none { inRing(p, it) } }
    }

    companion object {
        fun inRing(p: LatLon, ring: List<LatLon>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]; val b = ring[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) &&
                    p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
                ) inside = !inside
                j = i
            }
            return inside
        }

        fun distanceMeters(a: LatLon, b: LatLon): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(h))
        }
    }
}

class ZonesFile(val zones: List<Zone>, val issued: String?)

/** Lê o ficheiro ED-318 (GeoJSON) da ANAC. */
object ZoneParser {

    fun parse(text: String): ZonesFile {
        val clean = text.trimStart('﻿')
        val root = JSONObject(clean)
        val features = root.getJSONArray("features")
        val zones = ArrayList<Zone>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            runCatching { parseZone(f) }.getOrNull()?.let { zones.add(it) }
        }
        val issued = root.optJSONObject("metadata")?.optStringOrNull("issued")
        return ZonesFile(zones, issued)
    }

    private fun parseZone(f: JSONObject): Zone {
        val p = f.getJSONObject("properties")
        val g = f.getJSONObject("geometry")
        val layer = g.optJSONObject("layer")

        val polys = ArrayList<Poly>()
        var center: LatLon? = null
        var radius: Double? = null
        when (g.getString("type")) {
            "Polygon" -> polys.add(polygon(g.getJSONArray("coordinates")))
            "MultiPolygon" -> {
                val arr = g.getJSONArray("coordinates")
                for (i in 0 until arr.length()) polys.add(polygon(arr.getJSONArray(i)))
            }
            "Point" -> {
                val c = g.getJSONArray("coordinates")
                center = LatLon(c.getDouble(1), c.getDouble(0))
                radius = g.optJSONObject("extent")?.optDouble("radius")?.takeIf { !it.isNaN() } ?: 0.0
            }
        }

        val ext = p.optJSONObject("extendedProperties")
        return Zone(
            id = p.optString("identifier"),
            name = p.loc("name") ?: p.optString("identifier"),
            type = ZoneType.of(p.optStringOrNull("type")),
            reasons = p.optJSONArray("reason").strings(),
            otherReason = p.loc("otherReasonInfo"),
            message = p.loc("message"),
            lower = layer?.optDouble("lower")?.takeIf { !it.isNaN() },
            upper = layer?.optDouble("upper")?.takeIf { !it.isNaN() },
            lowerRef = layer?.optStringOrNull("lowerReference"),
            upperRef = layer?.optStringOrNull("upperReference"),
            uom = layer?.optStringOrNull("uom"),
            polys = polys,
            circleCenter = center,
            circleRadius = radius,
            periods = periods(p.optJSONArray("limitedApplicability")),
            authorities = authorities(p.optJSONArray("zoneAuthority")),
            colorHex = ext?.optStringOrNull("color"),
            notam = ext?.optStringOrNull("notam_inf"),
        )
    }

    private fun polygon(rings: JSONArray): Poly {
        val all = (0 until rings.length()).map { ring(rings.getJSONArray(it)) }
        return Poly(all.first(), all.drop(1))
    }

    private fun ring(arr: JSONArray): List<LatLon> =
        (0 until arr.length()).map {
            val c = arr.getJSONArray(it)
            LatLon(c.getDouble(1), c.getDouble(0))
        }

    private fun periods(arr: JSONArray?): List<Period> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Period(
                start = o.optStringOrNull("startDateTime")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                end = o.optStringOrNull("endDateTime")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                schedule = o.optJSONArray("schedule")?.let { Texts.schedule(it) },
            )
        }
    }

    private fun authorities(arr: JSONArray?): List<Authority> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Authority(
                name = o.loc("name"),
                service = o.loc("service"),
                contactName = o.loc("contactName"),
                email = o.optStringOrNull("email"),
                phone = o.optStringOrNull("phone"),
                site = o.optStringOrNull("siteURL"),
                purpose = o.optStringOrNull("purpose"),
                intervalBefore = o.optStringOrNull("intervalBefore"),
            )
        }
    }
}

/** Texto em português quando existe; aceita texto simples ou lista [{text, lang}]. */
fun JSONObject.loc(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = get(key)
    if (v is String) return v.ifBlank { null }
    if (v is JSONArray) {
        var first: String? = null
        for (i in 0 until v.length()) {
            val o = v.optJSONObject(i) ?: continue
            val t = o.optStringOrNull("text") ?: continue
            if (o.optString("lang").equals("PT", ignoreCase = true)) return t
            if (first == null) first = t
        }
        return first
    }
    return null
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }
