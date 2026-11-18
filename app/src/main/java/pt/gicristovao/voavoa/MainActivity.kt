package pt.gicristovao.voavoa

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.DashPathEffect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.time.Instant

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private val zonesFolder = FolderOverlay()
    private var pointMarker: Marker? = null

    private var zones: List<Zone> = emptyList()
    private var loadedAt = -1L
    private var refreshing = false
    private var lastError: String? = null
    private var defaultTextColor = Color.DKGRAY
    private var pendingInstall: File? = null
    private var updateChecked = false
    private var latestRelease: Updater.Release? = null
    private var infoSheet: InfoSheet? = null
    private lateinit var searchInput: EditText
    private lateinit var resultsCard: MaterialCardView
    private lateinit var resultsList: LinearLayout
    private lateinit var statusCard: View
    private var searchJob: Job? = null

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) enableLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        statusText = findViewById(R.id.statusText)
        defaultTextColor = statusText.currentTextColor

        setupMap()

        findViewById<MaterialButton>(R.id.btnCheck).setOnClickListener { checkHere() }
        findViewById<FloatingActionButton>(R.id.btnMyLocation).setOnClickListener { centerOnMe() }
        findViewById<View>(R.id.btnInfo).setOnClickListener { showInfo() }
        setupSearch()
        statusText.setOnClickListener { refresh(force = true) }

        ZoneRepository.scheduleBackground(this)
        requestLocationIfNeeded()
        showDisclaimerOnce()

        lifecycleScope.launch {
            loadFromDisk()
            refresh(force = true)
            checkForUpdate()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (hasLocationPermission()) locationOverlay.enableMyLocation()
        // Voltou das definições depois de autorizar a instalação: continua.
        pendingInstall?.let { apk ->
            if (packageManager.canRequestPackageInstalls()) {
                pendingInstall = null
                launchInstaller(apk)
            }
        }
        // Se a atualização em segundo plano trouxe dados novos, recarrega.
        if (loadedAt >= 0 && ZoneRepository.status(this).downloadedAt != loadedAt) {
            lifecycleScope.launch { loadFromDisk() }
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationOverlay.disableMyLocation()
    }

    // ---------- Mapa ----------

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.minZoomLevel = 4.0
        map.isTilesScaledToDpi = true
        map.controller.setZoom(7.0)
        map.controller.setCenter(GeoPoint(39.5, -8.0))

        val events = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                showPoint(LatLon(p.latitude, p.longitude), fromGps = false); return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        map.overlays.add(events)
        map.overlays.add(zonesFolder)

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        map.overlays.add(locationOverlay)
    }

    private fun drawZones() {
        zonesFolder.items.clear()
        val now = Instant.now()
        val density = resources.displayMetrics.density
        for (z in zones.filter { !it.isExpired(now) && !it.isNationwide }.sortedBy { it.type.rank }) {
            val future = z.isFuture(now)
            val base = zoneColor(z)
            val rings: List<Pair<List<GeoPoint>, List<List<GeoPoint>>>> =
                if (z.circleCenter != null && z.circleRadius != null) {
                    listOf(
                        Polygon.pointsAsCircle(
                            GeoPoint(z.circleCenter.lat, z.circleCenter.lon), z.circleRadius
                        ).toList() to emptyList()
                    )
                } else {
                    z.polys.map { p ->
                        p.outer.map { GeoPoint(it.lat, it.lon) } to
                            p.holes.map { h -> h.map { GeoPoint(it.lat, it.lon) } }
                    }
                }
            for ((outer, holes) in rings) {
                val poly = Polygon(map)
                poly.points = outer
                if (holes.isNotEmpty()) poly.holes = holes
                poly.fillPaint.color = withAlpha(base, if (future) 0x18 else 0x40)
                poly.outlinePaint.color = withAlpha(base, 0xDD)
                poly.outlinePaint.strokeWidth = 1.5f * density
                if (future) poly.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                poly.infoWindow = null
                poly.setOnClickListener { _, _, pos ->
                    showPoint(LatLon(pos.latitude, pos.longitude), fromGps = false); true
                }
                zonesFolder.add(poly)
            }
        }
        map.invalidate()
    }

    private fun zoneColor(z: Zone): Int {
        val hex = z.colorHex
        if (hex != null) runCatching { return Color.parseColor("#$hex") }
        return when (z.type) {
            ZoneType.PROHIBITED -> Color.RED
            ZoneType.REQ_AUTHORIZATION, ZoneType.CONDITIONAL -> Color.YELLOW
            else -> Color.GRAY
        }
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    // ---------- Dados ----------

    private suspend fun loadFromDisk() {
        val file = withContext(Dispatchers.Default) { ZoneRepository.load(this@MainActivity) }
        zones = file.zones
        loadedAt = ZoneRepository.status(this).downloadedAt
        drawZones()
        updateStatus()
    }

    private fun refresh(force: Boolean) {
        if (refreshing || !force) return
        refreshing = true
        statusText.text = "A atualizar zonas…"
        lifecycleScope.launch {
            val r = ZoneRepository.refresh(this@MainActivity)
            refreshing = false
            lastError = r.exceptionOrNull()?.let { "Sem ligação: a mostrar os dados guardados" }
            if (r.isSuccess) loadFromDisk() else updateStatus()
        }
    }

    private fun updateStatus() {
        val s = ZoneRepository.status(this)
        val issued = s.issued?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val now = Instant.now()
        val active = zones.count { it.isActive(now) && !it.isNationwide }
        val whenText = issued?.let { Texts.dateTime(it) } ?: "data desconhecida"
        val line1 = "Dados de $whenText · ${s.source}"
        val line2 = lastError ?: "$active zonas ativas · toca para atualizar"
        statusText.text = "$line1\n$line2"
        val old = issued == null || now.epochSecond - issued.epochSecond > 36 * 3600
        statusText.setTextColor(
            if (old || lastError != null) Color.parseColor("#E65100")
            else defaultTextColor
        )
    }

    // ---------- Localização ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationIfNeeded() {
        if (hasLocationPermission()) enableLocation()
        else askLocation.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun enableLocation() {
        locationOverlay.enableMyLocation()
        locationOverlay.runOnFirstFix {
            runOnUiThread {
                locationOverlay.myLocation?.let {
                    map.controller.animateTo(it, 12.0, 800L)
                }
            }
        }
    }

    private fun centerOnMe() {
        if (!hasLocationPermission()) { requestLocationIfNeeded(); return }
        val me = locationOverlay.myLocation
        if (me == null) {
            Toast.makeText(this, "À procura do sinal GPS…", Toast.LENGTH_SHORT).show()
            return
        }
        map.controller.animateTo(me, maxOf(map.zoomLevelDouble, 13.0), 600L)
    }

    private fun checkHere() {
        if (!hasLocationPermission()) { requestLocationIfNeeded(); return }
        val me = locationOverlay.myLocation
        if (me != null) {
            map.controller.animateTo(me, maxOf(map.zoomLevelDouble, 13.0), 600L)
            showPoint(LatLon(me.latitude, me.longitude), fromGps = true)
            return
        }
        Toast.makeText(this, "À procura do sinal GPS…", Toast.LENGTH_SHORT).show()
        locationOverlay.runOnFirstFix {
            runOnUiThread {
                locationOverlay.myLocation?.let {
                    map.controller.animateTo(it, maxOf(map.zoomLevelDouble, 13.0), 600L)
                    showPoint(LatLon(it.latitude, it.longitude), fromGps = true)
                }
            }
        }
    }

    // ---------- Consulta de um ponto ----------

    private fun showPoint(p: LatLon, fromGps: Boolean) {
        val now = Instant.now()
        val here = zones.filter { !it.isExpired(now) && it.contains(p) }
        val national = here.filter { it.isNationwide && it.isActive(now) }
        val active = here.filter { it.isActive(now) && !it.isNationwide }.sortedByDescending { it.type.rank }
        val upcoming = here.filter { it.isFuture(now) }
            .sortedBy { z -> z.periods.mapNotNull { it.start }.minOrNull() }

        val marker = pointMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.infoWindow = null
            it.setOnMarkerClickListener { _, _ -> true }
            map.overlays.add(it)
            pointMarker = it
        }
        marker.position = GeoPoint(p.lat, p.lon)
        map.invalidate()

        ZoneSheet.show(this, p, fromGps, active, upcoming, national)
    }

    // ---------- Pesquisa de locais ----------

    private fun setupSearch() {
        searchInput = findViewById(R.id.searchInput)
        resultsCard = findViewById(R.id.resultsCard)
        resultsList = findViewById(R.id.resultsList)
        statusCard = findViewById(R.id.statusRow)
        val clear = findViewById<ImageButton>(R.id.btnClear)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                if (q.length < 2) { hideResults(); return }
                if (!searchInput.hasFocus()) return
                searchJob = lifecycleScope.launch {
                    delay(450)
                    showResultsLoading()
                    showResults(PlaceSearch.geocoder(this@MainActivity, q, mapCenter()), submitted = false)
                }
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submitSearch(); true } else false
        }
        clear.setOnClickListener {
            searchInput.setText("")
            searchInput.requestFocus()
        }
    }

    private fun submitSearch() {
        val q = searchInput.text.toString().trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            showResultsLoading()
            var r = PlaceSearch.geocoder(this@MainActivity, q, mapCenter())
            if (r.isEmpty()) r = PlaceSearch.nominatim(q, mapCenter())
            showResults(r, submitted = true)
        }
    }

    private fun mapCenter() = map.mapCenter.let { LatLon(it.latitude, it.longitude) }

    private fun showResultsLoading() {
        resultsList.removeAllViews()
        resultsList.addView(resultMessage("A pesquisar…"))
        resultsCard.visibility = View.VISIBLE
        statusCard.visibility = View.GONE
    }

    private fun showResults(places: List<Place>, submitted: Boolean) {
        resultsList.removeAllViews()
        if (places.isEmpty()) {
            resultsList.addView(resultMessage(
                if (submitted) "Sem resultados em Portugal" else "Sem resultados. Carrega em pesquisar no teclado para procurar mais."
            ))
        } else {
            places.take(6).forEachIndexed { i, p -> resultsList.addView(resultRow(p, divider = i > 0)) }
        }
        resultsCard.visibility = View.VISIBLE
        statusCard.visibility = View.GONE
    }

    private fun hideResults() {
        resultsList.removeAllViews()
        resultsCard.visibility = View.GONE
        statusCard.visibility = View.VISIBLE
    }

    private fun resultMessage(msg: String) = TextView(this).apply {
        text = msg
        textSize = 13f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        val p = (14 * resources.displayMetrics.density).toInt()
        setPadding(p, p, p, p)
    }

    private fun resultRow(p: Place, divider: Boolean): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val muted = MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { goToPlace(p) }
        }
        row.addView(ImageView(this).apply {
            setImageResource(PlaceSearch.icon(p.kind))
            setColorFilter(muted)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = p.name; textSize = 15f })
            if (p.detail.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = p.detail; textSize = 12f; setTextColor(muted)
            })
        })
        if (!divider) return row
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity).apply {
                setBackgroundColor(MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOutlineVariant))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            addView(row)
        }
    }

    private fun goToPlace(p: Place) {
        searchJob?.cancel()
        searchInput.setText(p.name)
        searchInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        hideResults()
        val zoom = when (p.kind) {
            PlaceKind.LOCALITY -> 13.0
            PlaceKind.NATURE -> 14.0
            else -> 16.0
        }
        map.controller.animateTo(GeoPoint(p.lat, p.lon), zoom, 900L)
        showPoint(LatLon(p.lat, p.lon), fromGps = false)
    }

    // ---------- Nova versão da app ----------

    private suspend fun checkForUpdate() {
        if (updateChecked) return
        updateChecked = true
        val r = Updater.latest() ?: return
        latestRelease = r
        if (r.versionCode <= Updater.currentVersionCode(this)) return
        showUpdatePrompt(r)
    }

    private fun isNewer(r: Updater.Release?) = r != null && r.versionCode > Updater.currentVersionCode(this)

    private fun showUpdatePrompt(r: Updater.Release) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Nova versão da app")
            .setMessage(
                "Está disponível a versão ${r.versionName} (tens a ${Updater.currentVersionName(this)}).\n\n" +
                    "Os teus dados e definições mantêm-se."
            )
            .setPositiveButton("Instalar") { _, _ -> downloadAndInstall(r) }
            .setNegativeButton("Mais tarde", null)
            .show()
    }

    private fun downloadAndInstall(r: Updater.Release) {
        val progress: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("A descarregar")
            .setMessage("Versão ${r.versionName}…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val apk = runCatching { Updater.download(this@MainActivity, r.apkUrl) }.getOrNull()
            progress.dismiss()
            if (apk == null) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Não foi possível descarregar")
                    .setMessage("Podes instalar a versão nova a partir da página de versões no GitHub.")
                    .setPositiveButton("Abrir página") { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_PAGE))) }
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
                return@launch
            }
            if (packageManager.canRequestPackageInstalls()) {
                launchInstaller(apk)
            } else {
                pendingInstall = apk
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Autorizar instalação")
                    .setMessage(
                        "Para a Voa Voa se atualizar sozinha, o Android pede que a autorizes uma vez a instalar apps. " +
                            "Ativa a opção no ecrã seguinte e volta atrás."
                    )
                    .setPositiveButton("Continuar") { _, _ ->
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                            )
                        }
                    }
                    .setNegativeButton("Cancelar") { _, _ -> pendingInstall = null }
                    .show()
            }
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            startActivity(Updater.installIntent(this, apk))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir o instalador", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Informação ----------

    private fun showDisclaimerOnce() {
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("disclaimerShown", false)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Antes de voar")
            .setMessage(DISCLAIMER)
            .setPositiveButton("Percebi") { _, _ -> prefs.edit().putBoolean("disclaimerShown", true).apply() }
            .setCancelable(false)
            .show()
    }

    private fun showInfo() {
        infoSheet?.let { if (it.isOpen) { it.close(); return } }
        val s = ZoneRepository.status(this)
        val issued = s.issued?.let { runCatching { Texts.dateTime(Instant.parse(it)) }.getOrNull() } ?: "data desconhecida"
        val now = Instant.now()
        val active = zones.count { it.isActive(now) && !it.isNationwide }
        val sheet = InfoSheet(
            act = this,
            anchor = findViewById(R.id.btnInfo),
            dataLine = "$issued · ${s.source}",
            dataDetail = "$active zonas ativas · atualiza ao abrir e a cada 12 h",
            versionName = Updater.currentVersionName(this),
            onSearch = { sh -> searchForUpdate(sh) },
            onInstall = { r -> downloadAndInstall(r) },
        )
        infoSheet = sheet
        sheet.show(newer = latestRelease?.takeIf { isNewer(it) }, checked = latestRelease != null)
    }

    private fun searchForUpdate(sheet: InfoSheet) {
        lifecycleScope.launch {
            val r = Updater.latest()
            if (r == null) { sheet.searchFailed(); return@launch }
            latestRelease = r
            sheet.setAppState(newer = r.takeIf { isNewer(it) }, checked = true)
        }
    }

    companion object {
        const val DISCLAIMER =
            "Esta app não é oficial nem substitui a consulta das fontes oficiais (dnt.anac.pt). " +
                "Confirma sempre as restrições e as autorizações necessárias antes de voar."
    }
}
