package pt.gicristovao.voavoa.wear

import android.graphics.Color
import android.graphics.DashPathEffect
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import pt.gicristovao.voavoa.LatLon
import pt.gicristovao.voavoa.Zone
import java.time.Instant

/** Mapa redondo com o ponto marcado e as zonas próximas. Zoom com a coroa/bezel ou com dois dedos. */
@Composable
fun ZoneMap(center: LatLon, zones: List<Zone>, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val holder = remember { arrayOfNulls<MapView>(1) }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 6.0
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(center.lat, center.lon))
                drawZones(this, center, zones)
                overlays.add(Marker(this).apply {
                    position = GeoPoint(center.lat, center.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> true }
                })
                onResume()
                holder[0] = this
            }
        },
        modifier = modifier
            .onRotaryScrollEvent { e ->
                holder[0]?.controller?.let { if (e.verticalScrollPixels > 0) it.zoomIn() else it.zoomOut() }
                true
            }
            .focusRequester(focus)
            .focusable(),
    )

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose { holder[0]?.onDetach() }
    }
}

private fun drawZones(map: MapView, center: LatLon, zones: List<Zone>) {
    val now = Instant.now()
    val density = map.resources.displayMetrics.density
    // Só as zonas à volta do ponto, para o relógio não ficar lento.
    val near = zones.filter {
        !it.isExpired(now) && !it.isNationwide &&
            it.maxLat > center.lat - 0.25 && it.minLat < center.lat + 0.25 &&
            it.maxLon > center.lon - 0.3 && it.minLon < center.lon + 0.3
    }.sortedBy { it.type.rank }
    for (z in near) {
        val future = z.isFuture(now)
        val base = WearUi.zoneColor(z)
        val rings: List<Pair<List<GeoPoint>, List<List<GeoPoint>>>> =
            if (z.circleCenter != null && z.circleRadius != null) {
                listOf(Polygon.pointsAsCircle(GeoPoint(z.circleCenter.lat, z.circleCenter.lon), z.circleRadius).toList() to emptyList())
            } else {
                z.polys.map { p ->
                    p.outer.map { GeoPoint(it.lat, it.lon) } to p.holes.map { h -> h.map { GeoPoint(it.lat, it.lon) } }
                }
            }
        for ((outer, holes) in rings) {
            val poly = Polygon(map)
            poly.points = outer
            if (holes.isNotEmpty()) poly.holes = holes
            poly.fillPaint.color = Color.argb(if (future) 0x18 else 0x40, Color.red(base), Color.green(base), Color.blue(base))
            poly.outlinePaint.color = Color.argb(0xDD, Color.red(base), Color.green(base), Color.blue(base))
            poly.outlinePaint.strokeWidth = 1.5f * density
            if (future) poly.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            poly.infoWindow = null
            poly.setOnClickListener { _, _, _ -> false }
            map.overlays.add(poly)
        }
    }
}
