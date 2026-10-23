package com.example.toyotaobd.obd

/**
 * Erreur d'entrée/sortie sur la liaison vers l'adaptateur (équivalent multiplateforme de
 * `java.io.IOException`) : la liaison est perdue ou fermée.
 */
class TransportIOException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Lien série brut vers l'adaptateur ELM327.
 *
 * Implémentations : RFCOMM/SPP (Bluetooth classique, Android), GATT Bluetooth Low Energy (iOS),
 * simulateur (builds debug Android). Un transport BLE pourra être ajouté côté Android en
 * implémentant cette interface.
 */
interface ObdTransport {
    /**
     * Lecture bloquante et annulable : attend au moins un octet, copie au plus [buffer].size
     * octets dans [buffer]. Renvoie le nombre d'octets lus, ou -1 si la liaison est fermée.
     */
    suspend fun read(buffer: ByteArray): Int

    /** Écrit [data] sur la liaison et vide les tampons. */
    suspend fun write(data: ByteArray)

    fun close()
}
