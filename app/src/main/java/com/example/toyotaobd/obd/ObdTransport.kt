package com.example.toyotaobd.obd

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Lien série brut vers l'adaptateur ELM327.
 *
 * L'implémentation actuelle utilise le Bluetooth classique (RFCOMM/SPP). Un transport
 * Bluetooth Low Energy (GATT) pourra être ajouté en implémentant cette interface.
 */
interface ObdTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}
