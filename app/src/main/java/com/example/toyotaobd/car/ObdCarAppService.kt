package com.example.toyotaobd.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Point d'entrée Android Auto (Android for Cars App Library). La connexion OBD est celle de
 * l'application ([com.example.toyotaobd.session.ObdSession]) : téléphone, widget et voiture
 * affichent les mêmes lectures.
 */
class ObdCarAppService : CarAppService() {

    /** Debug : tout hôte (Desktop Head Unit compris) ; release : hôtes Android Auto signés par Google. */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // Une surface par session, enregistrée dès le départ : le tableau de bord y dessine à chaque connexion.
            val surface = CarSurface(carContext)
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
            return ConnectionCarScreen(carContext, surface)
        }
    }
}
