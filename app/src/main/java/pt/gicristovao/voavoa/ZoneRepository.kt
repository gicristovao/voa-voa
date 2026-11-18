package pt.gicristovao.voavoa

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Obtém e guarda as zonas.
 * Ordem: ANAC (direto) -> cópia no GitHub -> ficheiro guardado no telemóvel -> ficheiro incluído na app.
 */
object ZoneRepository {

    private const val ANAC_URL = "https://dnt.anac.pt/index.php?action=download-json"
    private const val MIRROR_URL = "https://raw.githubusercontent.com/gicristovao/voa-voa/main/data/zones.json"
    private const val MIN_ZONES = 50
    private const val PREFS = "zones"

    const val SOURCE_ANAC = "ANAC"
    const val SOURCE_MIRROR = "cópia automática"
    const val SOURCE_BUNDLED = "incluídos na app"

    data class Status(val issued: String?, val source: String, val downloadedAt: Long)

    private fun cache(ctx: Context) = File(ctx.filesDir, "zones.json")

    fun status(ctx: Context): Status {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Status(
            issued = p.getString("issued", null),
            source = p.getString("source", SOURCE_BUNDLED) ?: SOURCE_BUNDLED,
            downloadedAt = p.getLong("downloadedAt", 0L),
        )
    }

    /** Lê as zonas guardadas (ou as incluídas na app, se ainda não houver nenhuma). */
    fun load(ctx: Context): ZonesFile {
        val f = cache(ctx)
        if (f.exists()) {
            runCatching { ZoneParser.parse(f.readText()) }
                .getOrNull()?.takeIf { it.zones.size >= MIN_ZONES }?.let { return it }
        }
        val bundled = ctx.assets.open("zones.json").bufferedReader().use { it.readText() }
        return ZoneParser.parse(bundled)
    }

    /** Vai buscar dados novos. Devolve a fonte usada, ou erro se nenhuma funcionou. */
    suspend fun refresh(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        val current = status(ctx)
        for ((source, url) in listOf(SOURCE_ANAC to ANAC_URL, SOURCE_MIRROR to MIRROR_URL)) {
            try {
                val text = download(url)
                val parsed = ZoneParser.parse(text)
                if (parsed.zones.size < MIN_ZONES) throw IOException("Ficheiro com poucas zonas (${parsed.zones.size})")
                // Não substituir dados mais recentes por uma cópia mais antiga.
                if (source == SOURCE_MIRROR && current.issued != null && parsed.issued != null &&
                    parsed.issued < current.issued && cache(ctx).exists()
                ) return@withContext Result.success(current.source)
                save(ctx, text, parsed.issued, source)
                return@withContext Result.success(source)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        Result.failure(lastError ?: IOException("Sem ligação"))
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 45_000
        c.setRequestProperty("User-Agent", "voa-voa-android/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun save(ctx: Context, text: String, issued: String?, source: String) {
        val tmp = File(ctx.filesDir, "zones.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(cache(ctx))) {
            cache(ctx).writeText(text)
            tmp.delete()
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("issued", issued)
            .putString("source", source)
            .putLong("downloadedAt", System.currentTimeMillis())
            .apply()
    }

    /** Atualização em segundo plano, a cada 12 horas, quando houver rede. */
    fun scheduleBackground(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork("zones-update", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        if (ZoneRepository.refresh(applicationContext).isSuccess) Result.success() else Result.retry()
}
