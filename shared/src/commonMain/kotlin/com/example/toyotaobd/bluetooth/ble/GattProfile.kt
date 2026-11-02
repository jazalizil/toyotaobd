package com.example.toyotaobd.bluetooth.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Modèle GATT minimal, indépendant de la librairie BLE : la résolution du profil UART
 * ([UartProfileResolver]) et ses tests unitaires ne dépendent pas de Kable, qui n'intervient
 * que dans l'adaptateur ([KableBleAdapter]) via [KableGattMapper].
 */
@OptIn(ExperimentalUuidApi::class)
data class GattCharacteristic(
    val uuid: Uuid,
    val canNotify: Boolean,
    val canWriteWithResponse: Boolean,
    val canWriteWithoutResponse: Boolean,
)

@OptIn(ExperimentalUuidApi::class)
data class GattService(
    val uuid: Uuid,
    val characteristics: List<GattCharacteristic>,
) {
    fun characteristic(uuid: Uuid): GattCharacteristic? =
        characteristics.firstOrNull { it.uuid == uuid }
}
