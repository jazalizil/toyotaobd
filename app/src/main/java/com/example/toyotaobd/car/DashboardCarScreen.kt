package com.example.toyotaobd.car

import android.graphics.Bitmap
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.toyotaobd.R
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.atMostEvery
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Tableau de bord sur l'écran de la voiture, empilé par [ConnectionCarScreen] pendant une connexion :
 * un message fixe pendant la connexion, puis un onglet par section (infos principales, hybrides,
 * thermiques). Chaque onglet est un carrousel : une valeur à la fois, en plein écran avec son détail
 * ([carDetailRows], [pidImage]), et deux flèches pour passer à la précédente ou à la suivante. Il se
 * retire à la déconnexion.
 *
 * Quota de l'hôte (5 gabarits par tâche) : changer d'onglet, de valeur (mêmes titres de lignes pour
 * toutes) ou rafraîchir les lectures n'en consomme pas, et le message de connexion ne change pas
 * d'une étape à l'autre.
 */
class DashboardCarScreen(carContext: CarContext) : Screen(carContext) {

    private val session = carContext.obdSession
    private val dashboardStore = DashboardStore(carContext)
    private var activeSection = DashboardSection.MAIN

    /** Valeur affichée par chaque onglet (code du PID : une carte masquée ou déplacée ne décale rien). */
    private val currentCode = mutableMapOf<DashboardSection, Int>()
    private var imageSizeLogged = false

    init {
        Log.i(TAG, "API voiture de l'hôte : ${carContext.carAppApiLevel}")
        // Changement d'état aussitôt (les étapes de la connexion sont ignorées), valeurs au plus
        // toutes les 2 s (l'hôte limite lui-même la cadence).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.connection, session.live.atMostEvery(REFRESH_MS)) { connection, live ->
                    when (connection) {
                        is ConnectionState.Disconnected -> null
                        is ConnectionState.Connecting -> connection.deviceName
                        is ConnectionState.Connected -> connection.deviceName to live
                    }
                }
                    .distinctUntilChanged()
                    .collect { state -> if (state == null) finish() else invalidate() }
            }
        }
    }

    @Suppress("DEPRECATION") // setTitle / setHeaderAction : leur remplaçant (Header) exige l'API 7 de l'hôte.
    override fun onGetTemplate(): Template = when (val connection = session.connection.value) {
        is ConnectionState.Connected -> dashboard(connection.deviceName, session.live.value)
        // Déconnecté : l'écran se retire ; en attendant, même message que pendant la connexion.
        else -> {
            val name = (connection as? ConnectionState.Connecting)?.deviceName.orEmpty()
            val message = MessageTemplate.Builder("Connexion à l'adaptateur… Mettez le contact.")
                .setTitle(name.ifEmpty { carContext.getString(R.string.app_name) })
                .setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Annuler").setOnClickListener { session.cancelConnection() }.build())
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) message.setLoading(true)
            message.build()
        }
    }

    @Suppress("DEPRECATION")
    private fun dashboard(deviceName: String, live: LiveState): Template {
        val tabs = DashboardSection.present(live.pids.map { it.code })
        if (tabs.isEmpty()) return message(deviceName, "Aucune donnée supportée par le véhicule.")
        if (activeSection !in tabs) activeSection = tabs.first()

        // Hôte sans onglets (API < 6) ou une seule section : les premières valeurs, toutes sections confondues.
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_6 || tabs.size < 2) {
            val pane = carousel(tabs.first(), tabs, live) ?: return message(deviceName, NO_ITEMS)
            return PaneTemplate.Builder(pane)
                .setTitle(deviceName)
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val template = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeSection = DashboardSection.valueOf(tabContentId)
                invalidate()
            }
        }).setHeaderAction(Action.APP_ICON)
        for (section in tabs) {
            template.addTab(Tab.Builder().setTitle(section.tab).setIcon(tabIcon(section)).setContentId(section.name).build())
        }
        // Contenu d'onglet sans titre ni action d'en-tête (l'hôte les ignorerait).
        val content = carousel(activeSection, listOf(activeSection), live)?.let { PaneTemplate.Builder(it).build() }
            ?: MessageTemplate.Builder(NO_ITEMS).build()
        return template
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(activeSection.name)
            .build()
    }

    /**
     * Carrousel des valeurs de [sections] (cartes affichées du tableau de bord, dans leur ordre), dont la
     * position est retenue sous [key] ; les flèches font le tour. Null s'il n'y a aucune valeur.
     */
    private fun carousel(key: DashboardSection, sections: List<DashboardSection>, live: LiveState): Pane? {
        val layout = dashboardStore.load()
        val byCode = live.pids.associateBy { it.code }
        val pids = sections.flatMap { carRows(layout, live, it, Int.MAX_VALUE) }.mapNotNull { byCode[it.code] }
        if (pids.isEmpty()) return null
        val index = pids.indexOfFirst { it.code == currentCode[key] }.coerceAtLeast(0)
        val pid = pids[index]
        currentCode[key] = pid.code

        val samples = session.history.value[pid.code].orEmpty()
        val rows = carDetailRows(pid, live, samples, layout.windowMinutes, index, pids.size)
        fun show(offset: Int) {
            val target = pids[(index + offset).mod(pids.size)]
            currentCode[key] = target.code
            Log.i(TAG, "Carrousel ${key.name} : ${target.name} (${pids.indexOf(target) + 1}/${pids.size})")
            invalidate()
        }
        val several = pids.size > 1
        return carContext.detailPane(rows, image(pid, live, samples, layout.windowMinutes), { show(-1) }.takeIf { several }, { show(1) }.takeIf { several })
    }

    /** Image du carrousel (nom, valeur en grand, courbe ou volant), aux dimensions de l'écran de la voiture. */
    private fun image(pid: Pid, live: LiveState, samples: List<Sample>, windowMinutes: Int): Bitmap? {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_4) return null
        val density = carContext.resources.displayMetrics.density
        val (width, height) = carImageSize(density)
        if (!imageSizeLogged) {
            imageSizeLogged = true
            Log.i(TAG, "Image du carrousel : $width × $height px (densité de l'écran $density)")
        }
        val value = live.values[pid.code]
        val text = when {
            value != null -> pid.format(value)
            live.values.containsKey(pid.code) -> "n/d"
            else -> "…"
        }
        val last = samples.lastOrNull()?.timeMs
        val recent = if (last == null) emptyList() else samples.filter { it.timeMs >= last - windowMinutes * 60_000L }
        return pidImage(pid, text, recent, value, width, height)
    }

    @Suppress("DEPRECATION")
    private fun message(title: String, text: String): Template =
        MessageTemplate.Builder(text).setTitle(title).setHeaderAction(Action.APP_ICON).build()

    private fun tabIcon(section: DashboardSection): CarIcon {
        val icon = when (section) {
            DashboardSection.MAIN -> R.drawable.ic_car_tab_main
            DashboardSection.HYBRID -> R.drawable.ic_car_tab_hybrid
            DashboardSection.ENGINE -> R.drawable.ic_car_tab_engine
        }
        return CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build()
    }

    private companion object {
        const val TAG = "DashboardCarScreen"
        const val NO_ITEMS = "Aucune valeur affichée : personnalisez le tableau de bord sur le téléphone."
        const val REFRESH_MS = 2_000L
    }
}
