package com.example.toyotaobd.dev

import com.example.toyotaobd.dev.sim.Elm327Emulator
import com.example.toyotaobd.dev.sim.SimVehicles
import com.example.toyotaobd.dev.sim.SimulatedTransport

/** Adaptateurs simulés proposés dans l'écran de connexion des builds debug. */
object DevDevices {
    val simulators: List<SimulatedDevice> = listOf(
        SimulatedDevice(
            name = "Simulateur · Lexus UX 250h (TNGA)",
            description = "Données hybrides du calculateur 7D2 (service 22) · charge en mode 01",
        ) { SimulatedTransport(Elm327Emulator(SimVehicles.uxHybrid())) },
        SimulatedDevice(
            name = "Simulateur · Toyota hybride (CAN)",
            description = "Moteur, boîte et hybride HSD 3 (7E2) · 3 codes défaut · VIN · PID constructeur",
        ) { SimulatedTransport(Elm327Emulator(SimVehicles.hybridCan())) },
        SimulatedDevice(
            name = "Simulateur · Toyota ancienne (ISO 9141-2)",
            description = "Un seul calculateur · 4 codes défaut · pas de PID constructeur",
        ) { SimulatedTransport(Elm327Emulator(SimVehicles.legacyIso9141())) },
        SimulatedDevice(
            name = "Simulateur · Contact coupé",
            description = "Le véhicule ne répond pas (UNABLE TO CONNECT)",
        ) { SimulatedTransport(Elm327Emulator(SimVehicles.hybridCan(ignitionOn = false))) },
        SimulatedDevice(
            name = "Simulateur · Liaison instable",
            description = "Toyota hybride, coupure Bluetooth au bout de 20 s",
        ) { SimulatedTransport(Elm327Emulator(SimVehicles.hybridCan()), dropAfterMs = 20_000) },
    )
}
