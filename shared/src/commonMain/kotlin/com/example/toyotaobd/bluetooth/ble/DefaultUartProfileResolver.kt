package com.example.toyotaobd.bluetooth.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Résout le profil UART d'un adaptateur ELM327 BLE en parcourant une liste ordonnée de
 * candidats : la première entrée dont le service et les caractéristiques existent (avec les
 * bonnes propriétés) est retenue. **Principe ouvert/fermé** : ajouter un nouvel adaptateur
 * consiste à ajouter une entrée à [CANDIDATES], sans toucher à la logique de résolution.
 */
@OptIn(ExperimentalUuidApi::class)
object DefaultUartProfileResolver : UartProfileResolver {

    /** Profils « UART » connus, par ordre de préférence. */
    val CANDIDATES: List<UartProfile> = listOf(
        // Nordic UART Service (nombreux adaptateurs récents).
        UartProfile(
            service = u16(0x6E400001),
            notify = u16(0x6E400003),
            write = u16(0x6E400002),
        ),
        // Clones ELM327 BLE courants (FFF0) : les deux orientations existent selon le modèle.
        UartProfile(service = u16(0xFFF0), notify = u16(0xFFF1), write = u16(0xFFF2)),
        UartProfile(service = u16(0xFFF0), notify = u16(0xFFF2), write = u16(0xFFF1)),
        // Modules HM-10 (FFE0) : une seule caractéristique, notification et écriture.
        UartProfile(service = u16(0xFFE0), notify = u16(0xFFE1), write = u16(0xFFE1)),
        // Veepeak OBDCheck BLE+ (UART Texas Instruments).
        UartProfile(
            service = Uuid.parse("F0000110-0451-4000-B000-000000000000"),
            notify = Uuid.parse("F0000111-0451-4000-B000-000000000000"),
            write = Uuid.parse("F0000112-0451-4000-B000-000000000000"),
        ),
        // Autres modules BLE courants (18F0).
        UartProfile(service = u16(0x18F0), notify = u16(0x2AF1), write = u16(0x2AF0)),
    )

    override fun resolve(services: List<GattService>): ResolvedUart? =
        CANDIDATES.firstNotNullOfOrNull { candidate ->
            val service = services.firstOrNull { it.uuid == candidate.service }
                ?: return@firstNotNullOfOrNull null
            val notify = service.characteristic(candidate.notify)
                ?.takeIf { it.canNotify }
                ?: return@firstNotNullOfOrNull null
            val write = service.characteristic(candidate.write)
                ?: return@firstNotNullOfOrNull null
            if (!write.canWriteWithResponse && !write.canWriteWithoutResponse) {
                return@firstNotNullOfOrNull null
            }
            ResolvedUart(
                service = candidate.service,
                notify = notify.uuid,
                write = write.uuid,
                // Écriture avec accusé de réception quand la caractéristique le permet.
                writeType = if (write.canWriteWithResponse) {
                    UartWriteType.WITH_RESPONSE
                } else {
                    UartWriteType.WITHOUT_RESPONSE
                },
            )
        }

    /** UUID complet (base Bluetooth) d'un UUID 16 ou 32 bits, ex. `u16(0xFFF0)` ou `u16(0x6E400001)`. */
    private fun u16(short: Long): Uuid {
        // toString(16) + padStart : String.format n'existe pas en Kotlin/Native.
        val hex = (short and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
        return Uuid.parse("$hex-0000-1000-8000-00805F9B34FB")
    }
}
