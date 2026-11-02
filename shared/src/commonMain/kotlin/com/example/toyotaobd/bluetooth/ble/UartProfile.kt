package com.example.toyotaobd.bluetooth.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Profil « UART » d'un adaptateur ELM327 BLE : le service GATT et les deux caractéristiques
 * à utiliser, l'une en notification (réception), l'autre en écriture (envoi).
 */
@OptIn(ExperimentalUuidApi::class)
data class UartProfile(
    val service: Uuid,
    val notify: Uuid,
    val write: Uuid,
)

/** Type d'écriture GATT retenu pour la caractéristique d'écriture du profil. */
enum class UartWriteType {
    WITH_RESPONSE,
    WITHOUT_RESPONSE,
}

/** Profil UART retenu, avec le type d'écriture supporté par la caractéristique trouvée. */
@OptIn(ExperimentalUuidApi::class)
data class ResolvedUart(
    val service: Uuid,
    val notify: Uuid,
    val write: Uuid,
    val writeType: UartWriteType,
)

/**
 * Retrouve le profil UART d'un adaptateur à partir de son profil GATT découvert.
 * Implémentation par défaut : [DefaultUartProfileResolver].
 */
interface UartProfileResolver {
    fun resolve(services: List<GattService>): ResolvedUart?
}
