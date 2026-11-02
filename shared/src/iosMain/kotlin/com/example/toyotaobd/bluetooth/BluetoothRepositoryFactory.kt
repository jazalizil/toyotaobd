package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ble.KableBleAdapter

/**
 * Fabrique le dépôt Bluetooth de la cible iOS : BLE via Kable (CoreBluetooth), état de la
 * radio et autorisation lus sur CoreBluetooth, pas d'appareils appairés.
 */
fun createBluetoothRepository(): BluetoothRepository = KableBluetoothRepository(
    ble = KableBleAdapter(),
    radio = IosBluetoothRadio(),
    permissions = IosBluetoothPermissions(),
    pairedDevices = IosPairedDevicesProvider,
)
