package com.example.toyotaobd.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Liaison RFCOMM/SPP : le profil série utilisé par les adaptateurs ELM327 Bluetooth classiques. */
private class SppTransport(private val socket: BluetoothSocket) : ObdTransport {

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        socket.inputStream.read(buffer)
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            socket.outputStream.apply {
                write(data)
                flush()
            }
        }
    }

    override fun close() {
        socket.close()
    }
}

/**
 * Accès au Bluetooth classique. Toutes les méthodes supposent que [hasPermissions] est vrai :
 * sur Android 12+, les appels lèvent une SecurityException sans BLUETOOTH_CONNECT/SCAN.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothRepository(private val context: Context) : BluetoothRepository {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    override val isAvailable: Boolean get() = adapter != null
    override val isEnabled: Boolean get() = adapter?.isEnabled == true

    override val requiredPermissions: Array<String> = REQUIRED_PERMISSIONS

    override fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun pairedDevices(): List<BtDevice> =
        adapter?.bondedDevices.orEmpty()
            .map { it.toBtDevice() }
            .sortedWith(compareByDescending<BtDevice> { it.looksLikeObd }.thenBy { it.name })

    /** Recherche des appareils à proximité ; la recherche s'arrête quand le flux est annulé. */
    override fun discover(): Flow<BtDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> IntentCompat
                        .getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?.let { trySend(it.toBtDevice()) }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (adapter?.startDiscovery() != true) close(IOException("Impossible de lancer la recherche Bluetooth"))
        awaitClose {
            adapter?.cancelDiscovery()
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * Ouvre la liaison série. Si l'appareil n'est pas appairé, Android affiche la demande
     * de code PIN (généralement 1234 ou 0000). Plusieurs méthodes sont essayées car
     * certains clones ELM327 refusent la connexion sécurisée standard.
     */
    override suspend fun connect(address: String): ObdTransport = withContext(Dispatchers.IO) {
        val bt = adapter ?: throw TransportIOException("Bluetooth indisponible")
        val device = bt.getRemoteDevice(address)
        bt.cancelDiscovery()

        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Contournement historique pour les clones sans enregistrement SDP correct.
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (create in attempts) {
            ensureActive()
            val socket = runCatching(create).getOrElse {
                lastError = it as? Exception
                null
            } ?: continue
            try {
                socket.connect()
                // connect() est bloquant et non annulable : si l'utilisateur a annulé entre-temps, on referme.
                if (!isActive) {
                    socket.close()
                    throw CancellationException("Connexion annulée")
                }
                return@withContext SppTransport(socket)
            } catch (e: SecurityException) {
                runCatching { socket.close() }
                throw TransportIOException("Permission Bluetooth refusée", e)
            } catch (e: IOException) {
                lastError = e
                runCatching { socket.close() }
            }
        }
        throw TransportIOException(
            "Connexion impossible : ${lastError?.message ?: "erreur inconnue"}",
            lastError,
        )
    }

    private fun BluetoothDevice.toBtDevice() = BtDevice(
        name = name?.takeIf { it.isNotBlank() } ?: address,
        address = address,
        bonded = bondState == BluetoothDevice.BOND_BONDED,
    )

    companion object {
        /** Identifiant standard du profil série (Serial Port Profile). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        val REQUIRED_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                // Avant Android 12, la recherche d'appareils exige la localisation.
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
