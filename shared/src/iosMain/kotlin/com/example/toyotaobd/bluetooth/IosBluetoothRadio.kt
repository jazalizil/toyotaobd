package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ports.BluetoothRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.darwin.NSObject

/**
 * État de la radio Bluetooth sur iOS, suivi par un [CBCentralManager] dont le délégué alimente
 * [state] : l'état initial du gestionnaire est `unknown` et n'évolue que via
 * `centralManagerDidUpdateState`, d'où le flux réactif (une lecture ponctuelle à la composition
 * verrait `unknown` et afficherait « Bluetooth désactivé » à tort).
 *
 * La création du gestionnaire déclenche la demande d'autorisation Bluetooth du système.
 * (Le flux [state] part de DISABLED puis bascule dès le premier `centralManagerDidUpdateState`.)
 */
class IosBluetoothRadio : BluetoothRadio {

    /**
     * Délégué d'état : classe nommée instanciée (un `object` Kotlin étendant `NSObject` ferait
     * échouer le générateur de code natif).
     */
    private class StateDelegate(
        private val onState: (CBManagerState) -> Unit,
    ) : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            onState(central.state)
        }
    }

    private val _state = MutableStateFlow(BluetoothRadio.State.DISABLED)

    override val state: StateFlow<BluetoothRadio.State> = _state.asStateFlow()

    override val isAvailable: Boolean = true

    private val manager: CBCentralManager by lazy {
        CBCentralManager(StateDelegate { s -> _state.value = s.toRadioState() }, null).also {
            _state.value = it.state.toRadioState()
        }
    }

    init {
        manager // force la création : déclenche la demande d'autorisation et démarre le suivi d'état
    }

    private fun CBManagerState.toRadioState(): BluetoothRadio.State =
        if (this == CBManagerStatePoweredOn) BluetoothRadio.State.ENABLED else BluetoothRadio.State.DISABLED
}
