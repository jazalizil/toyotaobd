package com.example.toyotaobd.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.toyotaobd.bluetooth.ports.BluetoothPermissions

/** Autorisations d'exécution Bluetooth d'Android (déclarées dans le manifeste de l'app). */
class AndroidBluetoothPermissions(private val context: Context) : BluetoothPermissions {

    override val required: Array<String> get() = REQUIRED_PERMISSIONS

    override fun isGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // neverForLocation : la position n'est pas déduite du scan BLE (déclaré dans le manifeste).
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                // Avant Android 12, la recherche d'appareils exige la localisation.
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
