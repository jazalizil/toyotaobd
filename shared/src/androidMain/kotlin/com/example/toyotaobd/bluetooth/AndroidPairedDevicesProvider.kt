package com.example.toyotaobd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.toyotaobd.bluetooth.ports.PairedDevicesProvider

/** Appareils Bluetooth classique appairés au téléphone (bonded). */
@SuppressLint("MissingPermission") // bondedDevices exige BLUETOOTH_CONNECT : vérifiée par l'appelant.
class AndroidPairedDevicesProvider(context: Context) : PairedDevicesProvider {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun paired(): List<BtDevice> =
        adapter?.bondedDevices.orEmpty().map { it.toBtDevice() }

    private fun BluetoothDevice.toBtDevice() = BtDevice(
        name = name?.takeIf { it.isNotBlank() } ?: address,
        address = address,
        bonded = true,
    )
}
