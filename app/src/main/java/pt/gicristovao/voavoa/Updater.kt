package pt.gicristovao.voavoa

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Verifica no GitHub se há uma versão nova da app, descarrega-a e abre o instalador. */
object Updater {

    private const val LATEST_API = "https://api.github.com/repos/gicristovao/voa-voa/releases/latest"
    const val RELEASES_PAGE = "https://github.com/gicristovao/voa-voa/releases/latest"

    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    fun currentVersionCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Última versão publicada, ou null se não for possível saber. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(LATEST_API).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "voa-voa-android")
            try {
                if (c.responseCode != 200) return@withContext null
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = o.getString("tag_name")                       // ex.: v1.0.3
                val code = tag.substringAfterLast('.').toLongOrNull() ?: return@withContext null
                val assets = o.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (url.isNullOrBlank()) return@withContext null
                Release(code, tag.removePrefix("v"), url)
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    suspend fun download(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "voa-voa.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "voa-voa-android")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (out.length() < 100_000) throw IOException("Ficheiro incompleto")
        out
    }

    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
