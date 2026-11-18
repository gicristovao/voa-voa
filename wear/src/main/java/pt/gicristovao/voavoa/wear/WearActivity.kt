package pt.gicristovao.voavoa.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.tiles.TileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import pt.gicristovao.voavoa.LatLon
import pt.gicristovao.voavoa.Notam
import pt.gicristovao.voavoa.Place
import pt.gicristovao.voavoa.PlaceSearch
import pt.gicristovao.voavoa.Texts
import pt.gicristovao.voavoa.Verdict
import pt.gicristovao.voavoa.Zone
import pt.gicristovao.voavoa.ZoneRepository
import java.io.File
import java.time.Instant

/** Ponto a mostrar no mapa e no detalhe. */
data class Target(val name: String, val p: LatLon, val isMe: Boolean)

/** Estado da app do relógio. */
class WearState {
    var zones by mutableStateOf<List<Zone>>(emptyList())
    var dataIssued by mutableStateOf<String?>(null)
    var here by mutableStateOf<LatLon?>(null)
    var locating by mutableStateOf(false)
    var locationError by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Place>>(emptyList())
    var searching by mutableStateOf(false)
    var lastQuery by mutableStateOf("")
    var selected by mutableStateOf<Target?>(null)
}

class WearActivity : ComponentActivity() {

    private val state = WearState()

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) locate() else state.locationError = "Sem autorização para a localização"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        ZoneRepository.scheduleBackground(this)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        setContent {
            MaterialTheme {
                WearApp(state, version, ::locate, ::search, ::isOnline)
            }
        }

        lifecycleScope.launch {
            loadZones()
            if (ZoneRepository.refresh(this@WearActivity, preferMirror = true).isSuccess) loadZones()
        }
        if (WearLocation.hasPermission(this)) locate()
        else askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private suspend fun loadZones() {
        val f = withContext(Dispatchers.Default) { ZoneRepository.load(this@WearActivity) }
        state.zones = f.zones
        state.dataIssued = ZoneRepository.status(this).issued ?: f.issued
        updateTile()
    }

    private fun locate() {
        if (state.locating) return
        if (!WearLocation.hasPermission(this)) {
            askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        state.locating = true
        state.locationError = null
        lifecycleScope.launch {
            val p = WearLocation.current(this@WearActivity)
            state.locating = false
            if (p == null) state.locationError = "Sem sinal de GPS. Tenta ao ar livre."
            else { state.here = p; updateTile() }
        }
    }

    private fun search(q: String) {
        state.lastQuery = q
        state.searching = true
        state.results = emptyList()
        lifecycleScope.launch {
            val near = state.here ?: LatLon(39.5, -8.0)
            var r = PlaceSearch.photon(q, near)
            if (r.isEmpty()) r = PlaceSearch.nominatim(q, near)
            if (r.isEmpty()) r = PlaceSearch.geocoder(this@WearActivity, q, near)
            state.results = r
            state.searching = false
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null
    }

    private fun updateTile() {
        runCatching { TileService.getUpdater(this).requestUpdate(VerdictTileService::class.java) }
    }
}

// ---------------------------------------------------------------- navegação

@Composable
fun WearApp(
    state: WearState,
    version: String,
    onLocate: () -> Unit,
    onSearch: (String) -> Unit,
    isOnline: () -> Boolean,
) {
    val nav = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                state, version, onLocate,
                onDetail = { p -> state.selected = Target("A tua posição", p, true); nav.navigate("detail") },
                onMap = { p -> state.selected = Target("A tua posição", p, true); nav.navigate("map") },
                onSearch = { nav.navigate("search") },
            )
        }
        composable("detail") { DetailScreen(state) }
        composable("search") {
            SearchScreen(state, onSearch) { place ->
                state.selected = Target(place.name, LatLon(place.lat, place.lon), false)
                // Sem rede o mapa não carrega: salta logo para o veredicto.
                nav.navigate(if (isOnline()) "map" else "detail")
            }
        }
        composable("map") { MapScreen(state) { nav.navigate("detail") } }
    }
}

// ---------------------------------------------------------------- ecrã principal

@Composable
fun HomeScreen(
    state: WearState,
    version: String,
    onLocate: () -> Unit,
    onDetail: (LatLon) -> Unit,
    onMap: (LatLon) -> Unit,
    onSearch: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Text("A tua posição", fontSize = 12.sp, color = Color(0xFF9E9E9E)) }
            val here = state.here
            if (here != null && state.zones.isNotEmpty()) {
                val c = Verdict.check(state.zones, here)
                val sub = listOfNotNull(
                    Verdict.limit(c),
                    if (c.active.isNotEmpty()) "${c.active.size} ${if (c.active.size == 1) "zona" else "zonas"}" else null,
                ).joinToString(" · ")
                item { VerdictBadge(c.worst, sub) { onDetail(here) } }
                if (c.upcoming.isNotEmpty()) item {
                    Text("+ ${c.upcoming.size} restrição temporária futura", fontSize = 11.sp, color = Color(0xFFFFE08A), textAlign = TextAlign.Center)
                }
                item { WearChip("Ver zonas", { onDetail(here) }) }
                item { WearChip("Ver no mapa", { onMap(here) }) }
            } else if (state.locating || state.zones.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("A obter posição…", fontSize = 13.sp)
                    }
                }
            } else {
                item { Text(state.locationError ?: "Sem posição", fontSize = 13.sp, textAlign = TextAlign.Center) }
            }
            item { WearChip("Pesquisar local", onSearch) }
            item { WearChip(if (state.locating) "A localizar…" else "Atualizar posição", onLocate, secondary = true) }
            item {
                val issued = state.dataIssued?.let { runCatching { Texts.dateTime(Instant.parse(it)) }.getOrNull() }
                Text(
                    "Dados ANAC: ${issued ?: "—"}\nVoa Voa $version",
                    fontSize = 10.sp, color = Color(0xFF9E9E9E), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun VerdictBadge(type: pt.gicristovao.voavoa.ZoneType?, sub: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(WearUi.verdictColor(type)),
            contentAlignment = Alignment.Center,
        ) {
            Text(WearUi.symbol(type), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = WearUi.verdictInk(type))
        }
        Spacer(Modifier.height(6.dp))
        Text(WearUi.shortVerdict(type), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = Color(0xFFBDBDBD), textAlign = TextAlign.Center)
    }
}

@Composable
fun WearChip(label: String, onClick: () -> Unit, secondary: Boolean = false, sub: String? = null) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 2) },
        secondaryLabel = if (sub != null) { { Text(sub, maxLines = 1) } } else null,
        colors = if (secondary) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- detalhe das zonas

@Composable
fun DetailScreen(state: WearState) {
    val target = state.selected ?: return
    val c = Verdict.check(state.zones, target.p)
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(target.name, fontSize = 12.sp, color = Color(0xFF9E9E9E), textAlign = TextAlign.Center, maxLines = 2) }
            item { VerdictBadge(c.worst, Verdict.limit(c) ?: "") {} }
            if (c.active.isEmpty()) item {
                Text(
                    "Não há zonas da ANAC neste ponto. Aplicam-se as regras gerais (categoria aberta: até 120 m).",
                    fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
            c.active.forEach { z -> item { ZoneCard(z) } }
            if (c.upcoming.isNotEmpty()) {
                item { Text("Restrições futuras", fontSize = 12.sp, color = Color(0xFFFFE08A), modifier = Modifier.padding(top = 6.dp)) }
                c.upcoming.forEach { z -> item { ZoneCard(z) } }
            }
            item {
                Text(
                    "App não oficial. Confirma sempre em dnt.anac.pt.",
                    fontSize = 10.sp, color = Color(0xFF9E9E9E), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun ZoneCard(z: Zone) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(Texts.type(z.type), WearUi.verdictColor(z.type), WearUi.verdictInk(z.type))
                if (z.notam != null) Tag("NOTAM", Color(0xFFFFE08A), Color(0xFF5D4200))
            }
            Text(z.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (z.notam != null) {
                val n = Notam.parse(z.notam!!)
                val start = n.start ?: z.periods.firstOrNull()?.start
                val end = n.end ?: z.periods.firstOrNull()?.end
                listOfNotNull(
                    n.what?.replaceFirstChar { it.uppercase() },
                    Notam.period(start, end),
                    Notam.relative(Instant.now(), start, end),
                    n.schedule,
                    n.altitude,
                    n.exceptions?.let { "Exceções: $it" },
                ).forEach { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
            } else {
                Texts.altitude(z)?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
                z.message?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
                z.periods.firstOrNull()?.schedule?.let { Text(it, fontSize = 11.sp, color = Color(0xFFDDDDDD)) }
            }
            z.authorities.firstOrNull { it.purpose.equals("AUTHORIZATION", true) }?.let { a ->
                Text(
                    "Autorização: " + listOfNotNull(a.name, a.email, a.phone?.let { Texts.phoneForDial(it) }).joinToString("\n"),
                    fontSize = 10.sp, color = Color(0xFFBDBDBD),
                )
            }
        }
    }
}

@Composable
fun Tag(text: String, bg: Color, fg: Color) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------- pesquisa

@Composable
fun SearchScreen(state: WearState, onSearch: (String) -> Unit, onPick: (Place) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("q")?.toString()?.trim()
        if (!text.isNullOrBlank()) onSearch(text)
    }
    val openInput = {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent, listOf(RemoteInput.Builder("q").setLabel("Pesquisar local").build())
        )
        launcher.launch(intent)
    }
    // Ao entrar pela primeira vez, abre logo o teclado / voz.
    LaunchedEffect(Unit) { if (state.lastQuery.isBlank()) openInput() }

    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("Pesquisar local", fontSize = 12.sp, color = Color(0xFF9E9E9E)) }
            item { WearChip(if (state.lastQuery.isBlank()) "Voz ou teclado" else "\"${state.lastQuery}\"", { openInput() }, sub = "Tocar para pesquisar") }
            when {
                state.searching -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                state.lastQuery.isNotBlank() && state.results.isEmpty() -> item {
                    Text("Sem resultados em Portugal", fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                else -> state.results.take(6).forEach { p ->
                    item { WearChip(p.name, { onPick(p) }, secondary = true, sub = p.detail.ifBlank { null }) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- mapa de confirmação

@Composable
fun MapScreen(state: WearState, onVerdict: () -> Unit) {
    val target = state.selected ?: return
    val c = Verdict.check(state.zones, target.p)
    Box(modifier = Modifier.fillMaxSize()) {
        ZoneMap(target.p, state.zones, Modifier.fillMaxSize())
        Text(
            target.name, fontSize = 11.sp, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xA6000000)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .clickable(onClick = onVerdict).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(WearUi.verdictColor(c.worst)))
            Text(
                listOfNotNull(WearUi.shortVerdict(c.worst), Verdict.limit(c)?.takeIf { c.worst != null }).joinToString(" · "),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                fontFamily = FontFamily.Default,
            )
            Text("›", fontSize = 14.sp, color = Color(0xFFBDBDBD))
        }
    }
}
