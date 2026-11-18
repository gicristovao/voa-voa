package pt.gicristovao.voavoa.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import pt.gicristovao.voavoa.LatLon
import kotlin.coroutines.resume

/** Posição do relógio (GPS próprio ou do telemóvel), e a última posição guardada para o tile. */
object WearLocation {

    private const val PREFS = "location"

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(lm: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): LatLon? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val list = providers(lm)

        // Uma posição recente (menos de 2 minutos) serve logo.
        list.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 120_000 }
            .maxByOrNull { it.time }
            ?.let { return save(ctx, it) }

        for (p in list) {
            val loc = withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        lm.getCurrentLocation(p, signal, ctx.mainExecutor) { l -> if (cont.isActive) cont.resume(l) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (loc != null) return save(ctx, loc)
        }
        return null
    }

    /** Última posição conhecida (guardada pela app ou do sistema), com a hora em que foi obtida. */
    @SuppressLint("MissingPermission")
    fun last(ctx: Context): Pair<LatLon, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = if (p.contains("lat")) {
            LatLon(p.getString("lat", "0")!!.toDouble(), p.getString("lon", "0")!!.toDouble()) to p.getLong("time", 0)
        } else null
        if (!hasPermission(ctx)) return saved
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return saved
        val sys = providers(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        return if (sys != null && (saved == null || sys.time > saved.second)) LatLon(sys.latitude, sys.longitude) to sys.time else saved
    }

    private fun save(ctx: Context, l: Location): LatLon {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", l.latitude.toString())
            .putString("lon", l.longitude.toString())
            .putLong("time", System.currentTimeMillis())
            .apply()
        return LatLon(l.latitude, l.longitude)
    }
}
