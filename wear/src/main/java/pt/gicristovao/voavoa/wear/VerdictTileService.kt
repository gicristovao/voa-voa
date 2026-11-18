package pt.gicristovao.voavoa.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import pt.gicristovao.voavoa.Verdict
import pt.gicristovao.voavoa.Zone
import pt.gicristovao.voavoa.ZoneRepository
import pt.gicristovao.voavoa.ZoneType

/** Tile: veredicto da última posição conhecida. Tocar abre a app (que atualiza a posição e o tile). */
class VerdictTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            Thread {
                runCatching { completer.set(buildTile()) }.onFailure { completer.setException(it) }
            }.start()
            "voa-voa-tile"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
            "voa-voa-tile-res"
        }

    private fun buildTile(): TileBuilders.Tile {
        val zones = zones()
        val last = WearLocation.last(this)

        val (circle, ink, symbol, title, sub) = if (last == null) {
            Five(0xFF616161.toInt(), 0xFFFFFFFF.toInt(), "?", "Sem posição", "Toca para localizar")
        } else {
            val c = Verdict.check(zones, last.first)
            val t = c.worst
            val minutes = ((System.currentTimeMillis() - last.second) / 60_000).coerceAtLeast(0)
            val age = when {
                minutes < 1 -> "agora"
                minutes < 60 -> "há $minutes min"
                minutes < 60 * 24 -> "há ${minutes / 60} h"
                else -> "há ${minutes / (60 * 24)} dias"
            }
            Five(color(t), inkColor(t), WearUi.symbol(t), WearUi.shortVerdict(t), "Posição $age")
        }

        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WearActivity::class.java.name)
                            .build()
                    ).build()
            ).build()

        val layout = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text("Voa Voa", 12f, 0xFFBDBDBD.toInt()))
                    .addContent(spacer(8f))
                    .addContent(
                        LayoutElementBuilders.Box.Builder()
                            .setWidth(dp(48f)).setHeight(dp(48f))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setColor(argb(circle))
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(24f)).build())
                                            .build()
                                    ).build()
                            )
                            .addContent(text(symbol, 24f, ink, bold = true))
                            .build()
                    )
                    .addContent(spacer(6f))
                    .addContent(text(title, 15f, 0xFFFFFFFF.toInt(), bold = true))
                    .addContent(text(sub, 11f, 0xFF9E9E9E.toInt()))
                    .addContent(spacer(6f))
                    .addContent(text("Toca para atualizar", 11f, 0xFF90CAF9.toInt()))
                    .build()
            ).build()

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    private data class Five(val circle: Int, val ink: Int, val symbol: String, val title: String, val sub: String)

    private fun color(t: ZoneType?): Int = when (t) {
        ZoneType.PROHIBITED -> 0xFFC62828.toInt()
        ZoneType.REQ_AUTHORIZATION -> 0xFFF9A825.toInt()
        ZoneType.CONDITIONAL, ZoneType.UNKNOWN -> 0xFFFFF176.toInt()
        ZoneType.NO_RESTRICTION -> 0xFFB0BEC5.toInt()
        null -> 0xFF2E7D32.toInt()
    }

    private fun inkColor(t: ZoneType?): Int =
        if (t == ZoneType.PROHIBITED || t == null) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build()
            ).build()

    private fun spacer(h: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    /** As zonas ficam em memória enquanto os dados não mudarem. */
    private fun zones(): List<Zone> {
        val stamp = ZoneRepository.status(this).downloadedAt
        cache?.let { (s, z) -> if (s == stamp) return z }
        val z = ZoneRepository.load(this).zones
        cache = stamp to z
        return z
    }

    companion object {
        private const val RES_VERSION = "1"
        @Volatile private var cache: Pair<Long, List<Zone>>? = null
    }
}
