package com.example.toyotaobd.bluetooth

import android.content.Context
import com.example.toyotaobd.bluetooth.ble.KableBleAdapter

/**
 * Fabrique le dépôt Bluetooth de la cible Android : BLE via Kable, état de la radio et
 * autorisations lus sur le système, appareils appairés du Bluetooth classique.
 */
fun createBluetoothRepository(context: Context): BluetoothRepository = KableBluetoothRepository(
    ble = KableBleAdapter(),
    radio = AndroidBluetoothRadio(context),
    permissions = AndroidBluetoothPermissions(context),
    pairedDevices = AndroidPairedDevicesProvider(context),
)
