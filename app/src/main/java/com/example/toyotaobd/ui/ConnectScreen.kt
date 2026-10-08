package com.example.toyotaobd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.bluetooth.BtDevice
import com.example.toyotaobd.dev.SimulatedDevice
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.session.ConnectionState

@Composable
fun ConnectScreen(
    vm: ObdViewModel,
    connection: ConnectionState,
    bluetoothIssue: BluetoothIssue?,
    onFixBluetooth: () -> Unit,
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val protocol by vm.protocolChoice.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Toyota / Lexus OBD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            HelpCard()
        }
        if (connection is ConnectionState.Disconnected && connection.error != null) {
            item { ErrorCard(connection.error) }
        }
        item { ProtocolSelector(protocol, vm::selectProtocol) }

        if (vm.simulators.isNotEmpty()) {
            item { SectionTitle("Adaptateurs simulés (build debug)") }
            items(vm.simulators, key = { "s" + it.name }) { simulator ->
                SimulatorRow(simulator) { vm.connectSimulator(simulator) }
            }
        }

        if (bluetoothIssue != null) {
            item { BluetoothIssueCard(bluetoothIssue, onFixBluetooth) }
            return@LazyColumn
        }

        item { SectionTitle("Appareils appairés") }
        if (devices.paired.isEmpty()) {
            item { Text("Aucun appareil appairé. Lancez une recherche ou appairez l'adaptateur dans les réglages Bluetooth.") }
        }
        items(devices.paired, key = { "p" + it.address }) { DeviceRow(it) { vm.connect(it) } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Appareils à proximité", Modifier.weight(1f))
                if (devices.scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::stopDiscovery) { Text("Arrêter") }
                } else {
                    OutlinedButton(onClick = vm::startDiscovery) {
                        Icon(Icons.Default.Search, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rechercher")
                    }
                }
            }
        }
        devices.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(devices.discovered, key = { "d" + it.address }) { DeviceRow(it) { vm.connect(it) } }
    }

    if (connection is ConnectionState.Connecting) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelConnection) { Text("Annuler") } },
            title = { Text(connection.deviceName) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(connection.step)
                }
            },
        )
    }
}

@Composable
private fun HelpCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("1. Branchez l'adaptateur ELM327 sur la prise OBD (sous le volant, côté conducteur).")
            Text("2. Mettez le contact (ou démarrez le moteur).")
            Text("3. Appairez l'adaptateur dans les réglages Bluetooth d'Android (code PIN habituel : 1234 ou 0000).")
            Text(
                "Compatible avec les adaptateurs Bluetooth « classiques » (SPP). Les modèles Bluetooth Low Energy uniquement ne sont pas encore pris en charge.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ProtocolSelector(selected: ProtocolChoice, onSelect: (ProtocolChoice) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        SectionTitle("Protocole")
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.label, Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ProtocolChoice.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        leadingIcon = { if (choice == selected) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = {
                            onSelect(choice)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothIssueCard(issue: BluetoothIssue, onFix: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(issue.message + " Seuls les adaptateurs simulés sont utilisables.", color = MaterialTheme.colorScheme.onErrorContainer)
            issue.action?.let { OutlinedButton(onClick = onFix) { Text(it) } }
        }
    }
}

@Composable
private fun SimulatorRow(simulator: SimulatedDevice, onClick: () -> Unit) {
    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = { Text(simulator.name, fontWeight = FontWeight.Bold) },
            supportingContent = { Text(simulator.description) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun DeviceRow(device: BtDevice, onClick: () -> Unit) {
    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = {
                Text(device.name, fontWeight = if (device.looksLikeObd) FontWeight.Bold else FontWeight.Normal)
            },
            supportingContent = { Text(device.address + if (device.looksLikeObd) " · adaptateur OBD probable" else "") },
        )
        HorizontalDivider()
    }
}
