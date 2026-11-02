package com.example.toyotaobd.bluetooth.ports

import com.example.toyotaobd.bluetooth.BtDevice

/**
 * Appareils Bluetooth déjà appairés au système (port plateforme : appareils bonded Android,
 * liste vide sur iOS qui n'a pas d'appairage système).
 */
interface PairedDevicesProvider {
    fun paired(): List<BtDevice>
}
