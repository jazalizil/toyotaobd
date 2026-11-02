package com.example.toyotaobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.LiveState

/**
 * Écran d'accueil, inspiré des designs « AI car maintenance » (type EvoCare) : en-tête véhicule,
 * jauge circulaire de score de santé, carte assistant avec le point d'attention prioritaire,
 * actions rapides et métriques clés.
 */
@Composable
fun HomeScreen(
    vm: ObdViewModel,
    connection: ConnectionState.Connected,
    onOpenLive: () -> Unit,
    onOpenDtc: () -> Unit,
) {
    val live by vm.live.collectAsStateWithLifecycle()
    val dtc by vm.dtc.collectAsStateWithLifecycle()
    val values = live.values
    val coolant = values[Pids.COOLANT_TEMP.code]
    val fuel = values[Pids.FUEL_LEVEL.code]
    val voltage = live.voltage ?: values[Pids.MODULE_VOLTAGE.code]
    val dtcCount = dtc.codes.values.sumOf { it.size }
    val health = remember(coolant, voltage, fuel, dtc.readOnce, dtcCount) {
        computeHealth(coolant, voltage, fuel, dtcCount, dtc.readOnce)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HomeHeader(connection) }
        item { HealthCard(health) }
        item { AssistantCard(health, onOpenDtc) }
        item { QuickActions(onReadDtc = { vm.readDtcs(); onOpenDtc() }, onOpenLive = onOpenLive) }
        item { MetricsGrid(live) }
    }
}

@Composable
private fun HomeHeader(connection: ConnectionState.Connected) {
    val info = connection.info
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                connection.deviceName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column {
            Text(connection.deviceName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                buildString {
                    append(info.protocol.label)
                    info.vin?.let { append(" · VIN : $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthCard(health: HealthReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HealthGauge(health.score)
            Column(Modifier.weight(1f)) {
                Text("Score de santé", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${health.issues.size} point${if (health.issues.size > 1) "s" else ""} d'attention",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                health.top?.let {
                    Text(
                        it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (it.severity) {
                            HealthSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                            HealthSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                            HealthSeverity.INFO -> MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }
    }
}

/** Jauge circulaire : arc de 270° (135° → 405°), score au centre. */
@Composable
private fun HealthGauge(score: Int) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        score >= 80 -> Color(0xFF43A047)
        score >= 50 -> Color(0xFFFB8C00)
        else -> scheme.error
    }
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val stroke = 12.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val size = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = scheme.surfaceVariant,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * score / 100f,
                useCenter = false,
                topLeft = topLeft,
                size = size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
            Text("/ 100", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssistantCard(health: HealthReport, onOpenDtc: () -> Unit) {
    val top = health.top ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(top.message, style = MaterialTheme.typography.bodyMedium)
            if (top.severity != HealthSeverity.INFO) {
                TextButton(onClick = onOpenDtc, modifier = Modifier.align(Alignment.End)) {
                    Text("Voir les codes")
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun QuickActions(onReadDtc: () -> Unit, onOpenLive: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onReadDtc, modifier = Modifier.weight(1f)) { Text("Lire les codes défaut") }
        OutlinedButton(onClick = onOpenLive, modifier = Modifier.weight(1f)) { Text("Tableau de bord") }
    }
}

@Composable
private fun MetricsGrid(live: LiveState) {
    val metrics = listOf(
        "Régime" to Pids.RPM,
        "Vitesse" to Pids.SPEED,
        "Carburant" to Pids.FUEL_LEVEL,
        "Tension" to Pids.MODULE_VOLTAGE,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Métriques clés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, pid) ->
                    MetricCard(label, pid, live.values[pid.code] ?: live.voltage.takeIf { pid == Pids.MODULE_VOLTAGE }, Modifier.weight(1f))
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, pid: Pid, value: Double?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value?.let { "${pid.format(it)} ${pid.unit}".trim() } ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
