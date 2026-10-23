package com.example.toyotaobd.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.toyotaobd.R
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.platform.SharedPreferencesStore
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.atMostEvery
import com.example.toyotaobd.ui.chartEndMs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tableau de bord sur l'écran de la voiture, empilé par [ConnectionCarScreen] pendant une connexion :
 * un message fixe pendant la connexion, puis un onglet par section du tableau de bord (infos
 * principales, hybrides, thermiques). Chaque onglet est un carrousel dessiné sur la surface
 * ([CarSurface]) : au plus [CARDS_PER_PAGE] cartes à la fois, chacune sous la forme qu'elle a dans le
 * tableau de bord par défaut (courbe, jauge, volant ou valeur, voir [carRows]) avec son nom et sa
 * lecture, et deux flèches pour passer à la page précédente ou suivante. Il se retire à la
 * déconnexion.
 *
 * Onglets : des boutons dans la barre d'actions du gabarit de navigation (celui de la section
 * affichée sur fond de couleur), les flèches dans celle de la carte ; toucher un onglet ramène son
 * carrousel à la première page. Les vrais onglets d'Android Auto
 * ([androidx.car.app.model.TabTemplate]) peignent un fond opaque sur la surface (essayé sur un hôte
 * en API 8). Une seule section, ou un hôte en API < 2 (sans barre de la carte) : un seul carrousel
 * de toutes les valeurs, les flèches dans la barre d'actions.
 *
 * Quota de l'hôte (5 gabarits par tâche) : changer d'onglet, de page ou rafraîchir les lectures n'en
 * consomme pas (le gabarit de navigation prend tout changement pour un rafraîchissement, et seule la
 * surface est redessinée), et le message de connexion ne change pas d'une étape à l'autre.
 */
internal class DashboardCarScreen(carContext: CarContext, private val surface: CarSurface) : Screen(carContext) {

    /** Gabarit à montrer : seul un changement d'état en demande un nouveau à l'hôte. */
    private sealed interface Display {
        data class Connecting(val deviceName: String) : Display
        data class Message(val title: String, val text: String) : Display

        /** Carrousel de la section [active], avec un onglet par section de [tabs] ; sans onglets ([active] null) : toutes les valeurs. */
        data class Carousel(val tabs: List<DashboardSection>, val active: DashboardSection?) : Display
    }

    private val session = carContext.obdSession
    private val dashboardStore = DashboardStore(SharedPreferencesStore(carContext, "dashboard"))
    private var activeSection = DashboardSection.MAIN

    /** Page affichée par le carrousel, remise à la première à chaque onglet touché. */
    private var pageIndex = 0

    /** Dernier gabarit renvoyé à l'hôte. */
    private var shown: Display? = null

    init {
        Log.i(TAG, "API voiture de l'hôte : ${carContext.carAppApiLevel}")
        surface.content = ::page
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                surface.content = null
            }
        })
        // Changement d'état aussitôt (les étapes de la connexion sont ignorées), valeurs au plus
        // toutes les 2 s : nouveau gabarit si l'état change, sinon la surface seule est redessinée.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(session.connection, session.live.atMostEvery(REFRESH_MS), ::display).collect { display ->
                    when (display) {
                        null -> finish()
                        shown -> surface.redraw()
                        else -> invalidate()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION") // setTitle / setHeaderAction : leur remplaçant (Header) exige l'API 7 de l'hôte.
    override fun onGetTemplate(): Template {
        val display = display(session.connection.value, session.live.value)
        shown = display
        surface.redraw()
        return when (display) {
            is Display.Carousel -> carousel(display.tabs, display.active)

            is Display.Message -> MessageTemplate.Builder(display.text).setTitle(display.title).setHeaderAction(Action.APP_ICON).build()

            // Déconnecté : l'écran se retire ; en attendant, même message que pendant la connexion.
            is Display.Connecting, null -> {
                val name = (display as? Display.Connecting)?.deviceName.orEmpty()
                val message = MessageTemplate.Builder("Connexion à l'adaptateur… Mettez le contact.")
                    .setTitle(name.ifEmpty { carContext.getString(R.string.app_name) })
                    .setHeaderAction(Action.APP_ICON)
                    .addAction(Action.Builder().setTitle("Annuler").setOnClickListener { session.cancelConnection() }.build())
                if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) message.setLoading(true)
                message.build()
            }
        }
    }

    /** Null une fois déconnecté (l'écran se retire). */
    private fun display(connection: ConnectionState, live: LiveState): Display? = when (connection) {
        is ConnectionState.Disconnected -> null
        is ConnectionState.Connecting -> Display.Connecting(connection.deviceName)
        is ConnectionState.Connected -> {
            // Sections du tableau de bord ayant au moins une carte affichée, dans leur ordre.
            val sections = dashboardStore.load().bySection(live.pids.mapTo(mutableSetOf()) { it.code }).keys.toList()
            when {
                live.pids.isEmpty() -> Display.Message(connection.deviceName, "Aucune donnée supportée par le véhicule.")
                sections.isEmpty() -> Display.Message(connection.deviceName, NO_ITEMS)
                carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2 || sections.size < 2 -> Display.Carousel(emptyList(), null)
                else -> {
                    if (activeSection !in sections) {
                        activeSection = sections.first()
                        pageIndex = 0
                    }
                    Display.Carousel(sections, activeSection)
                }
            }
        }
    }

    /**
     * Gabarit du carrousel, le même pour toutes les pages : un bouton par section de [tabs] (celui de
     * [active] en évidence) et les flèches dans la barre de la carte ; sans onglets, les flèches seules.
     */
    private fun carousel(tabs: List<DashboardSection>, active: DashboardSection?): Template {
        val arrows = ActionStrip.Builder()
            .addAction(arrow(R.drawable.ic_car_previous) { turn(-1) })
            .addAction(arrow(R.drawable.ic_car_next) { turn(1) })
            .build()
        val template = NavigationTemplate.Builder()
        if (active == null) return template.setActionStrip(arrows).build()
        val sections = ActionStrip.Builder()
        for (section in tabs) sections.addAction(tab(section, selected = section == active))
        return template.setActionStrip(sections.build()).setMapActionStrip(arrows).build()
    }

    /**
     * Bouton d'onglet de [section], qui affiche sa première page ; [selected] : section affichée, sur
     * fond de couleur. Le gabarit de navigation n'accepte une couleur de fond que sur l'action
     * principale (API 4 de l'hôte) : en deçà, aucun onglet n'est mis en évidence.
     */
    private fun tab(section: DashboardSection, selected: Boolean): Action {
        val action = Action.Builder()
            .setTitle(section.tab)
            .setIcon(tabIcon(section))
            .setOnClickListener {
                pageIndex = 0
                if (activeSection != section) {
                    activeSection = section
                    invalidate()
                } else {
                    surface.redraw()
                }
            }
        if (selected && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) {
            action.setFlags(Action.FLAG_PRIMARY).setBackgroundColor(CarColor.BLUE)
        }
        return action.build()
    }

    /** Section du carrousel affiché (null : toutes les valeurs, sans onglets). */
    private val shownSection: DashboardSection?
        get() = (shown as? Display.Carousel)?.active

    /**
     * Page montrée par le carrousel affiché parmi [carRows] (cartes affichées de sa section, dans leur
     * ordre), par groupes de [CARDS_PER_PAGE] ; null hors carrousel ou sans valeur.
     */
    private fun page(): CarPage? {
        if (shown !is Display.Carousel) return null
        val section = shownSection
        val live = session.live.value
        val layout = dashboardStore.load()
        val rows = carRows(layout, live, section)
        if (rows.isEmpty()) return null
        val groups = rows.chunked(CARDS_PER_PAGE)
        pageIndex = pageIndex.mod(groups.size)
        val history = session.history.value
        val endMs = chartEndMs(history)
        val slides = groups[pageIndex].map { row ->
            CarSlide(row, live.pids.first { it.code == row.code }, live.values[row.code], history[row.code].orEmpty(), endMs, layout.windowMinutes)
        }
        // Même grille pour toutes les pages de la section : la dernière ne se réarrange pas.
        return CarPage(slides, rows.size.coerceAtMost(CARDS_PER_PAGE))
    }

    /** Passe à la page précédente ([offset] = -1) ou suivante (+1) ; les flèches font le tour. */
    private fun turn(offset: Int) {
        val section = shownSection
        val count = carRows(dashboardStore.load(), session.live.value, section).chunked(CARDS_PER_PAGE).size
        if (count == 0) return
        pageIndex = (pageIndex + offset).mod(count)
        Log.i(TAG, "Carrousel ${section?.name ?: "complet"} : page ${pageIndex + 1}/$count")
        surface.redraw()
    }

    private fun arrow(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()

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
