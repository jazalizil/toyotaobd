package com.example.toyotaobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.toyotaobd.obd.Dtc
import com.example.toyotaobd.obd.DtcMode
import com.example.toyotaobd.obd.Toyota

@Composable
fun DtcScreen(vm: ObdViewModel) {
    val state by vm.dtc.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!state.readOnce) vm.readDtcs() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::readDtcs, enabled = !state.loading) { Text("Relire") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = !state.loading) { Text("Effacer les codes") }
                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        state.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        state.status?.let { status ->
            item {
                val color = if (status.milOn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Voyant moteur : ${if (status.milOn) "ALLUMÉ" else "éteint"} · ${status.dtcCount} code(s) signalé(s)",
                        Modifier.padding(16.dp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (state.readOnce) {
            DtcMode.entries.forEach { mode ->
                val codes = state.codes[mode].orEmpty()
                item {
                    Text(
                        "${mode.label} (service ${mode.command}) : ${codes.size}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(codes, key = { "${mode.name}-${it.ecu}-${it.code}" }) { DtcRow(it) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer les codes défaut ?") },
            text = {
                Text(
                    "Le voyant moteur s'éteindra, mais les moniteurs de préparation (readiness) seront remis à zéro : " +
                        "le véhicule devra rouler plusieurs cycles avant un contrôle technique. " +
                        "Contact mis, moteur arrêté. Si le défaut persiste, le code reviendra.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearDtcs()
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun DtcRow(dtc: Dtc) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dtc.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("  ${Toyota.ecuName(dtc.ecu)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                dtc.description ?: when (dtc.code[1]) {
                    '1' -> "Code spécifique constructeur (voir documentation Toyota)"
                    else -> "Description non disponible"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
