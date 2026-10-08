package com.example.toyotaobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.obd.EnhancedPid
import com.example.toyotaobd.obd.EnhancedReading
import com.example.toyotaobd.obd.HeaderFormat
import com.example.toyotaobd.obd.Toyota
import com.example.toyotaobd.obd.VehicleInfo

@Composable
fun ToyotaScreen(vm: ObdViewModel, info: VehicleInfo) {
    val state by vm.enhanced.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    val canRead = info.protocol.headerFormat == HeaderFormat.CAN_11

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("PID constructeur Toyota / Lexus", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Calculateurs : " + Toyota.ECUS.joinToString { "${it.requestId} ${it.name}" } + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Les adresses et formules dépendent du modèle et de l'année : comparez toujours les octets bruts. " +
                            "Les octets A, B, C… suivent l'écho de la requête (61 xx ou 62 xx xx).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!canRead) {
                        Text(
                            "Indisponible avec le protocole ${info.protocol.label} : CAN 11 bits requis.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        items(state.pids, key = { it.id }) { pid ->
            EnhancedPidCard(
                pid = pid,
                reading = state.readings[pid.id],
                loading = pid.id in state.loading,
                enabled = canRead,
                onRead = { vm.readEnhanced(pid) },
                onDelete = { vm.deleteCustomPid(pid.id) }.takeUnless { pid.builtIn },
            )
        }
        item {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Ajouter un PID personnalisé")
            }
        }
    }

    if (showAdd) {
        AddPidDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, header, command, formula, unit ->
                vm.addCustomPid(name, header, command, formula, unit)
                showAdd = false
            },
        )
    }
}

@Composable
private fun EnhancedPidCard(
    pid: EnhancedPid,
    reading: EnhancedReading?,
    loading: Boolean,
    enabled: Boolean,
    onRead: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pid.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${pid.header} → ${pid.command} · ${pid.formula}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Supprimer") }
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Button(onClick = onRead, enabled = enabled) { Text("Lire") }
                }
            }
            pid.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            reading?.let { r ->
                r.value?.let {
                    Text("%.1f %s".format(it, pid.unit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                r.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (r.payload.isNotEmpty()) {
                    Text(
                        r.payload.mapIndexed { i, b -> "%c=%02X".format('A' + i, b) }.take(26).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPidDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var header by remember { mutableStateOf(Toyota.ENGINE.requestId) }
    var command by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("A") }
    var unit by remember { mutableStateOf("") }
    val error = Toyota.validate(header, command, formula)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau PID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Toyota.ECUS.forEach { ecu ->
                        FilterChip(
                            selected = header.equals(ecu.requestId, ignoreCase = true),
                            onClick = { header = ecu.requestId },
                            label = { Text(ecu.requestId) },
                        )
                    }
                }
                OutlinedTextField(header, { header = it.trim() }, label = { Text("En-tête (calculateur)") }, singleLine = true)
                OutlinedTextField(command, { command = it.trim() }, label = { Text("Commande (ex. 2182, 221E1C)") }, singleLine = true)
                OutlinedTextField(formula, { formula = it }, label = { Text("Formule (ex. A-40, ((A*256)+B)/80)") }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, label = { Text("Unité") }, singleLine = true)
                if (error != null && command.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, header, command, formula, unit) }, enabled = error == null) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
