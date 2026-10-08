package com.example.toyotaobd.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.example.toyotaobd.MainActivity
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.ui.SparklineRenderer
import com.example.toyotaobd.ui.steeringWheelBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import com.example.toyotaobd.session.atMostEvery

/**
 * Widget « tableau de bord » : toutes les valeurs en direct et leur tendance, sans défilement, pour
 * l'écran d'accueil du téléphone et les widgets Android Auto (construits avec Jetpack Glance).
 */
class DashboardWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val session = context.obdSession
        // Tendances sur la durée des courbes de l'application (relue à chaque session Glance).
        val windowMs = DashboardStore(context).load().windowMinutes * 60_000L
        provideContent {
            // État de connexion immédiat ; valeurs au plus toutes les 2 s, comme les updateAll()
            // d'ObdSession hors session Glance. Sinon le widget entier serait recomposé (et envoyé
            // au lanceur) à chaque lecture d'un PID.
            val connection by session.connection.collectAsState()
            val live by remember { session.live.atMostEvery(LIVE_REFRESH_MS) }.collectAsState(session.live.collectAsState().value)
            // Onglet choisi sur ce widget (chaque widget garde le sien, même après redémarrage).
            val section = currentState(SECTION_KEY)
            GlanceTheme(colors = widgetColors()) {
                DashboardContent(connection, live, session.history, windowMs, section)
            }
        }
    }
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

/** Onglet touché : la section est enregistrée dans l'état de ce widget, qui est redessiné. */
class SelectSectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val section = parameters[SECTION_PARAMETER] ?: return
        updateAppWidgetState(context, glanceId) { it[SECTION_KEY] = section }
        DashboardWidget().update(context, glanceId)
    }
}

private val SECTION_KEY = stringPreferencesKey("section")
private val SECTION_PARAMETER = ActionParameters.Key<String>("section")

/** Couleurs dynamiques du système (Android 12+), sinon thème clair/sombre de l'application. */
private val FallbackColors = ColorProviders(
    light = lightColorScheme(primary = Color(0xFFC8102E), secondaryContainer = Color(0xFFFFDAD7)),
    dark = darkColorScheme(primary = Color(0xFFFFB3B0), secondaryContainer = Color(0xFF5C1A1B)),
)

@Composable
private fun widgetColors(): ColorProviders =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else FallbackColors

/** Libellés courts pour les tuiles (les noms complets sont dans l'application). */
private val SHORT_LABELS = mapOf(
    0x04 to "Charge", 0x05 to "Refroid.", 0x06 to "STFT B1", 0x07 to "LTFT B1", 0x08 to "STFT B2",
    0x09 to "LTFT B2", 0x0B to "Collecteur", 0x0C to "Régime", 0x0D to "Vitesse", 0x0E to "Avance",
    0x0F to "Air adm.", 0x10 to "MAF", 0x11 to "Papillon", 0x1F to "Moteur depuis", 0x21 to "Dist. voyant",
    0x2F to "Carburant", 0x31 to "Dist. effac.", 0x33 to "Baro", 0x3C to "Catalyseur", 0x42 to "Tension ECU",
    0x46 to "Extérieur", 0x49 to "Pédale", 0x5C to "Huile", 0x5E to "Débit carb.", 0x200 to "Conso",
) + with(ToyotaHybrid) {
    mapOf(
        Pids.HYBRID_BATTERY to "Batt. HV", SOC to "Batt. HV", HV_POWER to "Puiss. HV", HV_CURRENT to "Courant HV",
        HSD3_HV_CURRENT to "Courant HV", HV_VOLTAGE to "Tension HV", HSD3_HV_VOLTAGE to "Tension HV",
        SOC_MAX to "Charge max", SOC_MIN to "Charge min", MOTOR_RPM to "Rég. élec.", MOTOR_TORQUE to "Couple élec.",
        REGEN_TORQUE to "Régén.", VL to "VL", VH to "VH", HSD3_VL to "VL", HSD3_VH to "VH",
        MOTOR_INVERTER_TEMP to "Ond. MG2", GENERATOR_INVERTER_TEMP to "Ond. MG1", MG1_INVERTER_TEMP to "Ond. MG1",
        MG2_INVERTER_TEMP to "Ond. MG2", BOOST_TEMP to "Convert.", HSD3_BOOST_TEMP to "Convert.",
        INVERTER_COOLANT to "Liq. ond.", HSD3_INVERTER_COOLANT to "Liq. ond.",
        BATTERY_TEMP_MAX to "Batt. max", BATTERY_TEMP_MIN to "Batt. min", HSD3_BATTERY_TEMP_MAX to "Batt. max",
        HSD3_BATTERY_TEMP_MIN to "Batt. min", BATTERY_FAN to "Ventil. batt.", TRANSAXLE_OIL to "Huile trans.",
        AIR_CONDITIONING to "Clim", CELL_MIN to "Élém. min", CELL_MAX to "Élém. max", CELL_SPREAD to "Δ éléments",
        MG1_RPM to "Rég. MG1", MG2_RPM to "Rég. MG2", MG1_TORQUE to "Couple MG1", MG2_TORQUE to "Couple MG2",
        MG1_TEMP to "Temp. MG1", MG2_TEMP to "Temp. MG2", BLOCK_MIN to "Bloc min", BLOCK_MAX to "Bloc max",
        BLOCK_SPREAD to "Δ blocs", RESISTANCE_MAX to "Résist. max", CHARGE_LIMIT to "Win",
        DISCHARGE_LIMIT to "Wout", DELTA_SOC to "Δ SOC", HSD3_BATTERY_FAN to "Ventil. batt.", STEERING_ANGLE to "Volant",
    ).mapKeys { it.key.code }
}

private const val LIVE_REFRESH_MS = 2_000L

private val HEADER_HEIGHT = 24.dp
/** Onglets compacts : au-delà, les tuiles d'un widget 4 × 2 deviennent trop basses pour leur tendance. */
private val TABS_HEIGHT = 24.dp
private val OUTER_PADDING = 6.dp

/** Marges d'une tuile : autour de la cellule, puis dans son fond. */
private val CELL_PADDING = 2.dp
private val TILE_PADDING_H = 6.dp
private val TILE_PADDING_V = 2.dp
private val LABEL_SIZE = 11.sp

/** Hauteur d'une ligne de TextView (marges de police comprises), en proportion de la taille du texte. */
private const val LINE_HEIGHT = 1.33f
private const val MIN_TREND_HEIGHT_DP = 14f

/** En dessous, le volant de la tuile « Volant » serait illisible : la tuile garde sa seule valeur. */
private const val MIN_WHEEL_DP = 20f

/** Dessin hors écran puis envoi des images au lanceur : pas plus d'une fois toutes les 2 s. */
private const val TREND_REFRESH_MS = 2_000L

/** [sectionName] : onglet choisi sur ce widget (nom de [DashboardSection]), null au départ. */
@Composable
private fun DashboardContent(
    connection: ConnectionState,
    live: LiveState,
    history: StateFlow<Map<Int, List<Sample>>>,
    windowMs: Long,
    sectionName: String?,
) {
    val size = LocalSize.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(OUTER_PADDING)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Header(connection, live)
        when (connection) {
            is ConnectionState.Connected -> {
                // Mêmes onglets que l'application : sections dont le véhicule fournit une valeur.
                val tabs = DashboardSection.present(live.pids.map { it.code })
                val section = tabs.firstOrNull { it.name == sectionName } ?: tabs.firstOrNull() ?: DashboardSection.MAIN
                val showTabs = tabs.size > 1
                if (showTabs) SectionTabs(tabs, section)
                TileGrid(
                    pids = live.pids.filter { DashboardSection.of(it.code) == section },
                    live = live,
                    history = history,
                    windowMs = windowMs,
                    width = size.width - OUTER_PADDING * 2,
                    height = size.height - OUTER_PADDING * 2 - HEADER_HEIGHT - if (showTabs) TABS_HEIGHT else 0.dp,
                )
            }
            is ConnectionState.Connecting -> CenteredMessage(connection.step)
            is ConnectionState.Disconnected -> CenteredMessage(
                connection.error?.let { "$it\nTouchez pour ouvrir l'application" }
                    ?: "Non connecté\nTouchez pour ouvrir l'application",
            )
        }
    }
}

@Composable
private fun Header(connection: ConnectionState, live: LiveState) {
    val connected = connection is ConnectionState.Connected
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            GlanceModifier.size(8.dp).cornerRadius(4.dp)
                .background(if (connected) GlanceTheme.colors.primary else GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = (connection as? ConnectionState.Connected)?.deviceName ?: "Toyota OBD",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        live.voltage?.takeIf { connected }?.let {
            Text("%.1f V".format(it), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

/** Onglets touchables ; ils changent la section affichée sans ouvrir l'application. */
@Composable
private fun SectionTabs(tabs: List<DashboardSection>, selected: DashboardSection) {
    Row(GlanceModifier.fillMaxWidth().height(TABS_HEIGHT).padding(bottom = 4.dp)) {
        for (tab in tabs) {
            val isSelected = tab == selected
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .padding(horizontal = CELL_PADDING)
                    .cornerRadius(8.dp)
                    .background(if (isSelected) GlanceTheme.colors.primary else GlanceTheme.colors.surfaceVariant)
                    .clickable(actionRunCallback<SelectSectionAction>(actionParametersOf(SECTION_PARAMETER to tab.name)))
                    .semantics { contentDescription = "Onglet ${tab.tab}" + if (isSelected) ", sélectionné" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.tab,
                    style = TextStyle(
                        color = if (isSelected) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = LABEL_SIZE,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(GlanceModifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
}

private data class Tile(val label: String, val value: String, val unit: String, val pid: Pid? = null)

/** Tuiles de [pids] (valeurs de l'onglet choisi), avec les lectures de [live]. */
@SuppressLint("LocalContextConfigurationRead")
@Composable
private fun TileGrid(
    pids: List<Pid>,
    live: LiveState,
    history: StateFlow<Map<Int, List<Sample>>>,
    windowMs: Long,
    width: Dp,
    height: Dp,
) {
    val tiles = pids.map { pid ->
        val value = live.values[pid.code]
        Tile(
            label = SHORT_LABELS[pid.code] ?: pid.name,
            value = value?.let(pid::format) ?: if (live.values.containsKey(pid.code)) "n/d" else "…",
            unit = pid.unit,
            pid = pid,
        )
    }
    if (tiles.isEmpty()) {
        CenteredMessage("Aucune donnée supportée par le véhicule")
        return
    }
    val plan = WidgetGrid.plan(tiles.size, width.value, height.value)
    val cellWidth = width.value / plan.columns
    val cellHeight = height.value / plan.rows
    val valueSize: TextUnit = when {
        cellHeight >= 70 -> 22.sp
        cellHeight >= 54 -> 18.sp
        else -> 15.sp
    }
    // LocalConfiguration de Compose n'existe pas dans Glance (« CompositionLocal LocalConfiguration not
    // present », le widget affiche alors « Can't show content ») : la configuration vient du contexte.
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val trendArea = trendArea(cellWidth, cellHeight, valueSize, fontScale)
    // L'angle du volant est représenté par un volant (comme dans l'application), pas par une tendance.
    val visiblePids = tiles.take(plan.visibleTiles).mapNotNull { it.pid }
    val steering = visiblePids.firstOrNull { it.code == ToyotaHybrid.STEERING_ANGLE.code }
    // Toute la hauteur de la tuile, en filigrane derrière le texte (au plus les deux tiers de sa largeur).
    val wheelDp = min(
        cellHeight - (CELL_PADDING + TILE_PADDING_V).value * 2,
        (cellWidth - (CELL_PADDING + TILE_PADDING_H).value * 2) * 2 / 3,
    )
    val wheel = rememberSteeringWheel(steering?.let { live.values[it.code] }, wheelDp)
    val trends = rememberTrends(visiblePids - setOfNotNull(steering), trendArea, history, windowMs)

    Column(GlanceModifier.fillMaxSize()) {
        for (row in 0 until plan.rows) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (column in 0 until plan.columns) {
                    val index = row * plan.columns + column
                    val cell = GlanceModifier.defaultWeight().fillMaxHeight().padding(CELL_PADDING)
                    when {
                        index < plan.visibleTiles -> {
                            val tile = tiles[index]
                            val tileWheel = wheel.takeIf { steering != null && tile.pid == steering }
                            TileCell(tile, valueSize, trendArea, tile.pid?.let { trends[it.code] }, cell, tileWheel, wheelDp)
                        }
                        index == plan.visibleTiles && plan.hiddenTiles > 0 ->
                            TileCell(Tile("Dans l'app", "+${plan.hiddenTiles}", ""), valueSize, trendArea, null, cell)
                        else -> Spacer(cell)
                    }
                }
            }
        }
    }
}

/**
 * Zone de la tendance dans le fond d'une tuile, en dp : [topDp] sous le haut, sur [widthDp] × [heightDp].
 * [behindText] : elle passe derrière la valeur, plus pâle.
 */
private data class TrendArea(val topDp: Float, val widthDp: Float, val heightDp: Float, val behindText: Boolean)

/**
 * Sous la valeur s'il y a la place ; sinon derrière sa moitié basse (cas des widgets de taille
 * courante, aux tuiles basses). Null si la tuile est vraiment trop petite.
 */
private fun trendArea(cellWidth: Float, cellHeight: Float, valueSize: TextUnit, fontScale: Float): TrendArea? {
    val labelHeight = LABEL_SIZE.value * LINE_HEIGHT * fontScale
    val valueHeight = valueSize.value * LINE_HEIGHT * fontScale
    val width = cellWidth - (CELL_PADDING + TILE_PADDING_H).value * 2
    val height = cellHeight - (CELL_PADDING + TILE_PADDING_V).value * 2
    val below = height - labelHeight - valueHeight
    val area = if (below >= MIN_TREND_HEIGHT_DP) {
        TrendArea(labelHeight + valueHeight, width, below, behindText = false)
    } else {
        val top = labelHeight + valueHeight / 2
        TrendArea(top, width, height - top, behindText = true)
    }
    return area.takeIf { width > 0 && area.heightDp >= MIN_TREND_HEIGHT_DP }
}

/**
 * Tendance de chaque PID de [pids] (code → image), dessinée par MPAndroidChart hors écran : un widget
 * n'affiche pas de vue personnalisée. Redessinée au plus toutes les [TREND_REFRESH_MS].
 */
@Composable
private fun rememberTrends(
    pids: List<Pid>,
    size: TrendArea?,
    history: StateFlow<Map<Int, List<Sample>>>,
    windowMs: Long,
): Map<Int, Bitmap> {
    val context = LocalContext.current
    val trends by produceState(emptyMap<Int, Bitmap>(), pids, size, windowMs) {
        if (size == null || pids.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        val density = context.resources.displayMetrics.density
        val pixelsPerDp = WidgetGrid.trendPixelsPerDp(pids.size, size.widthDp, size.heightDp, density)
        val renderer = withContext(Dispatchers.Main) { SparklineRenderer(context, size.widthDp, size.heightDp, pixelsPerDp, size.behindText) }
        // StateFlow : pendant la pause, seules les dernières valeurs sont gardées.
        history.collect { all ->
            // Comme les courbes de l'application : la fenêtre se termine à la dernière lecture, tous PID confondus.
            val nowMs = all.values.maxOfOrNull { it.lastOrNull()?.timeMs ?: Long.MIN_VALUE } ?: 0L
            value = withContext(Dispatchers.Main) {
                pids.mapNotNull { pid ->
                    val recent = all[pid.code].orEmpty().filter { it.timeMs >= nowMs - windowMs }
                    renderer.render(recent, pid.range)?.let { pid.code to it }
                }.toMap()
            }
            delay(TREND_REFRESH_MS)
        }
    }
    return trends
}

/**
 * Volant de la tuile « Volant », tourné de l'angle [angle] et carré de [sizeDp] ; redessiné à chaque
 * nouvelle valeur (au plus toutes les 2 s : un widget ne s'anime pas). Null sans valeur ou si la place manque.
 */
@Composable
private fun rememberSteeringWheel(angle: Double?, sizeDp: Float): Bitmap? {
    val density = LocalContext.current.resources.displayMetrics.density
    return remember(angle, sizeDp, density) {
        if (angle == null || sizeDp < MIN_WHEEL_DP) null else steeringWheelBitmap((sizeDp * density).roundToInt(), density, angle)
    }
}

/**
 * [area] : place des tendances dans la grille ; [trend] est absente tant qu'il y a moins de deux lectures.
 * [wheel] : volant de [wheelDp] de côté, à droite de la tuile et derrière son texte (tuile « Volant »).
 */
@Composable
private fun TileCell(
    tile: Tile,
    valueSize: TextUnit,
    area: TrendArea?,
    trend: Bitmap?,
    modifier: GlanceModifier,
    wheel: Bitmap? = null,
    wheelDp: Float = 0f,
) {
    Box(modifier) {
        Box(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.secondaryContainer)
                .cornerRadius(10.dp)
                .padding(horizontal = TILE_PADDING_H, vertical = TILE_PADDING_V),
        ) {
            if (wheel != null) {
                // Dessiné en blanc, teinté ici comme les tendances.
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Image(
                        provider = ImageProvider(wheel),
                        contentDescription = null,
                        modifier = GlanceModifier.size(wheelDp.dp),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    )
                }
            }
            if (area != null && trend != null) {
                // Tracée en blanc, teintée ici : elle suit le thème clair ou sombre et les couleurs dynamiques.
                Image(
                    provider = ImageProvider(trend),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize().padding(top = area.topDp.dp),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
            }
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = if (area != null) Alignment.Top else Alignment.CenterVertically,
            ) {
                Text(
                    tile.label,
                    style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = LABEL_SIZE),
                    maxLines = 1,
                )
                Text(
                    if (tile.unit.isEmpty()) tile.value else "${tile.value} ${tile.unit}",
                    style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = valueSize, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
    }
}
