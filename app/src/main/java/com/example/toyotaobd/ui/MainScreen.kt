package com.example.toyotaobd.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.session.ConnectionState

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Données", Icons.Default.Home),
    DTC("Codes", Icons.Default.Warning),
    TOYOTA("Toyota", Icons.Default.Build),
    CONSOLE("Console", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: ObdViewModel, connection: ConnectionState.Connected) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    RequestNotificationPermissionOnce()
    val live by vm.live.collectAsStateWithLifecycle()
    val info = connection.info

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(connection.deviceName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(info.protocol.label)
                                live.voltage?.let { append(" · %.1f V".format(it)) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = { TextButton(onClick = { vm.disconnect() }) { Text("Déconnecter") } },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            when (tab) {
                Tab.LIVE -> DashboardScreen(vm, info)
                Tab.DTC -> DtcScreen(vm)
                Tab.TOYOTA -> ToyotaScreen(vm, info)
                Tab.CONSOLE -> ConsoleScreen(vm)
            }
        }
    }
}

/**
 * La notification du service de connexion affiche les valeurs clés et l'action « Déconnecter ».
 * Facultative : refusée, la connexion reste maintenue mais la notification est masquée.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
