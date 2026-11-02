package com.example.toyotaobd.bluetooth.ports

import kotlinx.coroutines.flow.StateFlow

/**
 * État de la radio Bluetooth de l'appareil (port plateforme : [androidMain] suit
 * `BluetoothAdapter.ACTION_STATE_CHANGED`, [iosMain] suit le `CBCentralManager`).
 */
interface BluetoothRadio {

    enum class State {
        UNAVAILABLE,
        DISABLED,
        ENABLED,
    }

    /** L'appareil dispose d'une radio Bluetooth (Low Energy). */
    val isAvailable: Boolean

    /**
     * État de la radio en flux : il peut changer hors de l'application (l'utilisateur bascule le
     * Bluetooth dans les réglages système). Ne jamais lire un état ponctuel à la composition :
     * collecter ce flux pour que l'UI réagisse.
     */
    val state: StateFlow<State>

    /** Lecture ponctanée de [state] (à réserver aux appels hors composition). */
    val isEnabled: Boolean get() = state.value == State.ENABLED
}
