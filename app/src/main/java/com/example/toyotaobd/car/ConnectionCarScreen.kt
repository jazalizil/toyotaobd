package com.example.toyotaobd.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.toyotaobd.R
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.session.ConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Premier écran Android Auto : « non connecté » et choix de l'adaptateur. Dès qu'une connexion
 * commence (depuis la voiture ou le téléphone), il empile [DashboardCarScreen], qui se retire à la
 * déconnexion.
 *
 * Pourquoi deux écrans : l'hôte limite une tâche à 5 gabarits et seuls les rafraîchissements (même
 * contenu principal) n'en consomment pas ; revenir à un écran rend les gabarits de ceux qu'on quitte.
 * Ainsi la pile ne dépasse jamais 3 gabarits, quel que soit le nombre de connexions et déconnexions.
 * Au retour, l'écran doit renvoyer le même type de gabarit qu'avant : toujours un message ici.
 */
class ConnectionCarScreen(carContext: CarContext) : Screen(carContext) {

    private val session = carContext.obdSession

    init {
        lifecycleScope.launch {
            // Seulement au premier plan : sous le tableau de bord, cet écran ne réagit pas.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.connection
                    .map { (it as? ConnectionState.Disconnected)?.error.orEmpty() to (it is ConnectionState.Disconnected) }
                    .distinctUntilChanged()
                    .collect { (_, disconnected) ->
                        if (!disconnected) {
                            if (screenManager.top === this@ConnectionCarScreen) screenManager.push(DashboardCarScreen(carContext))
                        } else {
                            invalidate()
                        }
                    }
            }
        }
    }

    @Suppress("DEPRECATION") // setTitle / setHeaderAction : leur remplaçant (Header) exige l'API 7 de l'hôte.
    override fun onGetTemplate(): Template {
        val connection = session.connection.value
        val builder = MessageTemplate.Builder(
            when (connection) {
                is ConnectionState.Disconnected -> connection.error ?: "Adaptateur OBD non connecté."
                else -> "Connexion en cours…" // aussitôt remplacé par le tableau de bord
            },
        )
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
        if (connection is ConnectionState.Disconnected) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Choisir l'adaptateur")
                    .setOnClickListener { screenManager.push(AdapterCarScreen(carContext)) }
                    .build(),
            )
        }
        return builder.build()
    }
}
