@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.toyotaobd.bluetooth.ble

import com.juul.kable.DiscoveredService
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse

/**
 * Convertit le profil GATT découvert par Kable en [modèle indépendant de la librairie][GattService],
 * consommé par [UartProfileResolver]. Seul endroit où les types Kable fuient dans le modèle.
 */
fun DiscoveredService.toGattService(): GattService = GattService(
    uuid = serviceUuid,
    characteristics = characteristics.map { c ->
        GattCharacteristic(
            uuid = c.characteristicUuid,
            canNotify = c.properties.notify || c.properties.indicate,
            canWriteWithResponse = c.properties.write,
            canWriteWithoutResponse = c.properties.writeWithoutResponse,
        )
    },
)
