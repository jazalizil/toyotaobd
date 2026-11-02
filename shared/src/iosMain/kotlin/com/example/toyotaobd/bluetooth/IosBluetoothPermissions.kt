package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ports.BluetoothPermissions
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways

/**
 * Autorisation Bluetooth d'iOS : demandée par le système à la première utilisation de
 * CoreBluetooth (déjà déclarée via `NSBluetoothAlwaysUsageDescription`), aucune permission
 * à réclamer dans l'interface.
 */
class IosBluetoothPermissions : BluetoothPermissions {

    override val required: Array<String> = emptyArray()

    override fun isGranted(): Boolean =
        CBManager.authorization == CBManagerAuthorizationAllowedAlways
}
