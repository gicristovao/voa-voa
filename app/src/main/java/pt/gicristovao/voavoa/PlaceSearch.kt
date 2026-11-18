package pt.gicristovao.voavoa

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

enum class PlaceKind { LOCALITY, LANDMARK, NATURE, TRANSPORT, ADDRESS, OTHER }

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val kind: PlaceKind)

/**
 * Pesquisa de locais em Portugal (continente, Açores e Madeira).
 * Enquanto se escreve: Photon (OpenStreetMap, feito para pesquisa à medida que se escreve).
 * Ao carregar em "Pesquisar" sem resultados: Nominatim e, por fim, o Geocoder do Android.
 * O Nominatim não permite pesquisa a cada tecla, por isso só é usado ao submeter.
 */
object PlaceSearch {

    // Caixa que inclui o continente e as ilhas.
    private const val MIN_LAT = 32.3
    private const val MIN_LON = -31.6
    private const val MAX_LAT = 42.2
    private const val MAX_LON = -6.1
    private val pt = Locale("pt", "PT")

    suspend fun photon(query: String, near: LatLon): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://photon.komoot.io/api/?q=$q&limit=12" +
                "&lat=${near.lat}&lon=${near.lon}&bbox=$MIN_LON,$MIN_LAT,$MAX_LON,$MAX_LAT"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "voa-voa-android (https://github.com/gicristovao/voa-voa)")
            try {
                if (c.responseCode != 200) return@withContext emptyList<Place>()
                val feats = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("features")
                (0 until feats.length()).mapNotNull { i ->
                    val f = feats.getJSONObject(i)
                    val p = f.getJSONObject("properties")
                    if (!p.optString("countrycode").equals("PT", true)) return@mapNotNull null
                    val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val street = p.str("street")
                    val name = p.str("name")
                        ?: street?.let { listOfNotNull(it, p.str("housenumber")).joinToString(" ") }
                        ?: return@mapNotNull null
                    val detail = listOfNotNull(p.str("district"), p.str("locality"), p.str("city"), p.str("county"), p.str("state"))
                        .filter { !it.equals(name, true) }.distinct().take(2).joinToString(", ")
                    Place(name, detail, lat, lon, photonKind(p.optString("osm_key"), p.optString("osm_value"), p.optString("type")))
                }.mergeDuplicates().take(8)
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.str(k: String): String? = optString(k, "").trim().ifBlank { null }

    private val transportValues = setOf(
        "station", "halt", "stop", "tram_stop", "subway_entrance", "bus_stop", "bus_station",
        "platform", "stop_position", "ferry_terminal",
    )

    private fun photonKind(key: String, value: String, type: String): PlaceKind = when {
        key == "railway" || key == "public_transport" || value in transportValues -> PlaceKind.TRANSPORT
        key == "place" || type in setOf("city", "locality", "district", "county") -> PlaceKind.LOCALITY
        key in setOf("leisure", "natural", "landuse", "waterway") || value in setOf("park", "beach", "peak", "nature_reserve") ->
            PlaceKind.NATURE
        key == "highway" || (type == "house" && key in setOf("building", "place", "")) || type == "street" -> PlaceKind.ADDRESS
        key in setOf("amenity", "tourism", "historic", "building", "man_made", "railway", "aeroway", "office", "shop") -> PlaceKind.LANDMARK
        else -> PlaceKind.OTHER
    }

    suspend fun geocoder(ctx: Context, query: String, near: LatLon): List<Place> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext emptyList<Place>()
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(ctx, pt).getFromLocationName(query, 8, MIN_LAT, MIN_LON, MAX_LAT, MAX_LON)
                .orEmpty()
                .filter { it.countryCode.isNullOrBlank() || it.countryCode.equals("PT", true) }
                .mapNotNull { fromAddress(it, query) }
        }.getOrDefault(emptyList()).mergeDuplicates().sortedByNear(near)
    }

    private fun fromAddress(a: Address, query: String): Place? {
        if (!a.hasLatitude() || !a.hasLongitude()) return null
        val feature = a.featureName?.takeIf { it.isNotBlank() }
        val numeric = feature?.all { it.isDigit() || it == '-' || it == ' ' } == true
        val kind = when {
            numeric || (a.thoroughfare != null && feature == a.subThoroughfare) -> PlaceKind.ADDRESS
            feature != null && (feature == a.locality || feature == a.subAdminArea || feature == a.subLocality) -> PlaceKind.LOCALITY
            feature != null -> PlaceKind.LANDMARK
            else -> PlaceKind.OTHER
        }
        val name = when {
            kind == PlaceKind.ADDRESS && a.thoroughfare != null -> listOfNotNull(a.thoroughfare, feature).joinToString(" ")
            feature != null -> feature
            else -> a.locality ?: query
        }
        val detail = listOfNotNull(a.subLocality, a.locality, a.subAdminArea, a.adminArea)
            .filter { it.isNotBlank() && !it.equals(name, true) }
            .distinct().take(2).joinToString(", ")
        return Place(name, detail, a.latitude, a.longitude, kind)
    }

    suspend fun nominatim(query: String, near: LatLon): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query, "UTF-8")
            val box = "${near.lon - 0.6},${near.lat + 0.6},${near.lon + 0.6},${near.lat - 0.6}"
            val url = "https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&countrycodes=pt" +
                "&limit=8&accept-language=pt&viewbox=$box&bounded=0"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "voa-voa-android (https://github.com/gicristovao/voa-voa)")
            try {
                if (c.responseCode != 200) return@withContext emptyList<Place>()
                val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val parts = o.optString("display_name").split(",").map { it.trim() }
                        .filter { it.isNotBlank() && it != "Portugal" && !Regex("""^\d{4}(-\d{3})?$""").matches(it) }
                    val name = o.optString("name").ifBlank { parts.firstOrNull() ?: return@mapNotNull null }
                    val detail = parts.filter { !it.equals(name, true) }.take(2).joinToString(", ")
                    Place(
                        name, detail,
                        o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null,
                        o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null,
                        kindOf(o.optString("category"), o.optString("type"), o.optString("addresstype")),
                    )
                }.mergeDuplicates()
            } finally {
                c.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    private fun kindOf(category: String, type: String, addressType: String): PlaceKind = when {
        category == "railway" || category == "public_transport" || type in transportValues -> PlaceKind.TRANSPORT
        category == "place" || addressType in setOf("city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "municipality") ->
            PlaceKind.LOCALITY
        category in setOf("leisure", "natural", "landuse", "waterway") || type in setOf("park", "beach", "peak", "nature_reserve") ->
            PlaceKind.NATURE
        category in setOf("highway") || addressType in setOf("road", "house_number") -> PlaceKind.ADDRESS
        category in setOf("amenity", "tourism", "historic", "building", "man_made", "railway", "aeroway") -> PlaceKind.LANDMARK
        else -> PlaceKind.OTHER
    }

    private fun norm(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(pt).trim()

    /**
     * O OpenStreetMap regista muitas vezes o mesmo sítio várias vezes (edifício, terreno, sala…).
     * Resultados com o mesmo nome a menos de 300 m passam a um só; fica o primeiro (mais relevante),
     * com o tipo mais informativo entre os repetidos.
     */
    private fun List<Place>.mergeDuplicates(): List<Place> {
        val out = ArrayList<Place>()
        for (p in this) {
            val i = out.indexOfFirst {
                norm(it.name) == norm(p.name) && Zone.distanceMeters(LatLon(it.lat, it.lon), LatLon(p.lat, p.lon)) < 300
            }
            if (i < 0) { out.add(p); continue }
            val kept = out[i]
            if (kept.kind in setOf(PlaceKind.OTHER, PlaceKind.ADDRESS) && p.kind !in setOf(PlaceKind.OTHER, PlaceKind.ADDRESS)) {
                out[i] = kept.copy(kind = p.kind)
            }
        }
        return out
    }

    private fun List<Place>.sortedByNear(near: LatLon) =
        sortedBy { Zone.distanceMeters(near, LatLon(it.lat, it.lon)) }

    fun icon(kind: PlaceKind): Int = when (kind) {
        PlaceKind.LOCALITY -> R.drawable.ic_city
        PlaceKind.LANDMARK -> R.drawable.ic_landmark
        PlaceKind.NATURE -> R.drawable.ic_park
        PlaceKind.TRANSPORT -> R.drawable.ic_transit
        PlaceKind.ADDRESS -> R.drawable.ic_home
        PlaceKind.OTHER -> R.drawable.ic_place
    }
}
