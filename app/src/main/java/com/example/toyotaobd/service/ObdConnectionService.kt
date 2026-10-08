package com.example.toyotaobd.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.toyotaobd.MainActivity
import com.example.toyotaobd.R
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui maintient la connexion OBD quand l'application n'est plus visible
 * (téléphone verrouillé, Android Auto). Il s'arrête de lui-même à la déconnexion.
 */
class ObdConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = obdSession
        ensureChannel()
        // startForeground doit toujours être appelé, même si le service s'arrête juste après.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(session.connection.value, session.live.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Passage au premier plan refusé", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (session.connection.value !is ConnectionState.Connected) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                combine(session.connection, session.live, ::Pair).conflate().collect { (connection, live) ->
                    if (connection !is ConnectionState.Connected) {
                        ServiceCompat.stopForeground(this@ObdConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        cancel()
                        return@collect
                    }
                    notify(buildNotification(connection, live))
                    delay(NOTIFICATION_REFRESH_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Connexion OBD")
                .setDescription("Affichée tant que l'adaptateur OBD est connecté")
                .build(),
        )
    }

    private fun notify(notification: Notification) {
        // Sans POST_NOTIFICATIONS (Android 13+), le service tourne mais la notification est masquée.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(connection: ConnectionState, live: LiveState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getBroadcast(
            this, 0, Intent(this, DisconnectReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = (connection as? ConnectionState.Connected)?.let { "Connecté · ${it.deviceName}" } ?: "Connexion OBD"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(title)
            .setContentText(summary(live))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(0, "Déconnecter", disconnect)
            .build()
    }

    /** Quelques valeurs clés, ex. « 2 450 tr/min · 35 km/h · 82 °C · 14,1 V ». */
    private fun summary(live: LiveState): String {
        val values = listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP).mapNotNull { pid ->
            live.values[pid.code]?.let { "${pid.format(it)} ${pid.unit}" }
        }
        val voltage = live.voltage?.let { "%.1f V".format(it) }
        return (values + listOfNotNull(voltage)).joinToString(" · ").ifEmpty { "En attente des données…" }
    }

    companion object {
        private const val TAG = "ObdConnectionService"
        private const val CHANNEL_ID = "obd_connection"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_REFRESH_MS = 2_000L
    }
}

/** Action « Déconnecter » de la notification. */
class DisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.obdSession.disconnect()
    }
}
