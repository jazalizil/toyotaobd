package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ports.PairedDevicesProvider

/** iOS n'a pas d'appairage système : la liste est vide, la recherche BLE suffit. */
object IosPairedDevicesProvider : PairedDevicesProvider {
    override fun paired(): List<BtDevice> = emptyList()
}
