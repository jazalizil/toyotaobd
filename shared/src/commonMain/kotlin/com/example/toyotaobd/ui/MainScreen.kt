package com.example.toyotaobd.ui

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
import com.example.toyotaobd.util.fmt

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
                                live.voltage?.let { append(" · %.1f V".fmt(it)) }
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
