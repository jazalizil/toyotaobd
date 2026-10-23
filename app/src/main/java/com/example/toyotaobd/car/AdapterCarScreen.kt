package com.example.toyotaobd.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import com.example.toyotaobd.bluetooth.AndroidBluetoothRepository
import com.example.toyotaobd.dev.DevDevices
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.obdSession

/**
 * Choix de l'adaptateur depuis la voiture : adaptateurs appairés au téléphone, puis simulateurs
 * (builds debug). Toucher une ligne lance la connexion et revient au tableau de bord, qui affiche
 * la progression. Les autorisations Bluetooth se donnent sur le téléphone (pas de dialogue en voiture).
 */
class AdapterCarScreen(carContext: CarContext) : Screen(carContext) {

    private val session = carContext.obdSession
    private val bluetooth = AndroidBluetoothRepository(carContext)

    @Suppress("DEPRECATION") // setTitle / setHeaderAction : leur remplaçant (Header) exige l'API 7 de l'hôte.
    override fun onGetTemplate(): Template {
        val usable = bluetooth.isAvailable && bluetooth.hasPermissions() && bluetooth.isEnabled
        val rows = buildList {
            if (usable) {
                for (device in bluetooth.pairedDevices()) {
                    add(row(device.name, device.address, "Connexion Bluetooth…") { bluetooth.connect(device.address) })
                }
            }
            for (simulator in DevDevices.simulators) {
                add(row(simulator.name, simulator.description, "Ouverture du simulateur…") { simulator.open() })
            }
        }
        val items = ItemList.Builder().setNoItemsMessage(
            if (usable) {
                "Aucun adaptateur appairé : appairez l'ELM327 dans les réglages Bluetooth du téléphone."
            } else {
                "Bluetooth indisponible : activez-le et autorisez-le dans l'application, sur le téléphone."
            },
        )
        rows.take(listLimit()).forEach(items::addItem)
        return ListTemplate.Builder()
            .setTitle("Adaptateur OBD")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }

    /**
     * Ligne qui lance la connexion puis revient tout de suite à l'écran d'accueil (même type de gabarit
     * qu'avant, règle de l'hôte au retour), qui empile alors le tableau de bord.
     */
    private fun row(title: String, text: String, firstStep: String, open: suspend () -> ObdTransport): Row =
        Row.Builder()
            .setTitle(title)
            .addText(text)
            .setOnClickListener {
                session.connect(title, firstStep, ProtocolChoice.TOYOTA_AUTO, open)
                screenManager.pop()
            }
            .build()

    private fun listLimit(): Int =
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            6
        }
}
