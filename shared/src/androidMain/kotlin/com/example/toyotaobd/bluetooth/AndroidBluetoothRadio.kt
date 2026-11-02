package com.example.toyotaobd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.example.toyotaobd.bluetooth.ports.BluetoothRadio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * État de la radio Bluetooth lu sur le [BluetoothAdapter] du système, et suivi en continu via
 * [BluetoothAdapter.ACTION_STATE_CHANGED] : [state] reflète les bascules faites depuis les
 * réglages rapides pendant que l'application tourne.
 */
@SuppressLint("MissingPermission") // isEnabled exige BLUETOOTH_CONNECT (API 31+) : vérifiée par l'appelant.
@Suppress("DEPRECATION") // BluetoothAdapter.isEnabled, remplacé par BluetoothManager.adapterState (API 33).
class AndroidBluetoothRadio(context: Context) : BluetoothRadio {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(adapter.toState())

    override val state: StateFlow<BluetoothRadio.State> = _state.asStateFlow()

    override val isAvailable: Boolean get() = adapter != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> _state.value = BluetoothRadio.State.ENABLED
                    BluetoothAdapter.STATE_OFF -> _state.value = BluetoothRadio.State.DISABLED
                    else -> Unit // états transitoires (STATE_TURNING_ON/OFF) : on attend le suivant
                }
            }
        }
    }

    init {
        if (adapter != null) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun BluetoothAdapter?.toState(): BluetoothRadio.State = when {
        this == null -> BluetoothRadio.State.UNAVAILABLE
        isEnabled -> BluetoothRadio.State.ENABLED
        else -> BluetoothRadio.State.DISABLED
    }
}
