package com.fluxdevx.mtk

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PartitionManagerScreen(
    scatter: ScatterFile?,
    scatterName: String?,
    selectedPartition: ScatterPartition?,
    imageName: String?,
    imageSize: Long?,
    operation: String?,
    progress: Float,
    message: String,
    onPickScatter: () -> Unit,
    onSelectPartition: (ScatterPartition) -> Unit,
    onPickImage: () -> Unit,
    onReadback: () -> Unit,
    onFlash: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Partition manager", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Scatter-driven MediaTek readback and flashing", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPickScatter) { Text("Load scatter") }
                        scatterName?.let { Text(it, modifier = Modifier.padding(top = 10.dp)) }
                    }
                    if (scatter != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("${scatter.partitions.size} partitions • ${scatter.format}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (scatter != null) {
            item { Text("Partitions", style = MaterialTheme.typography.titleMedium) }
            items(scatter.partitions, key = { it.name }) { partition ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(partition.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${partition.fileName ?: "No image"} • " +
                                "size=${partition.partitionSize?.let(::formatBytes) ?: "unknown"} • " +
                                "${if (partition.isDownload) "downloadable" else "not for download"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onSelectPartition(partition) }) { Text(if (selectedPartition?.name == partition.name) "Selected" else "Select") }
                    }
                }
            }
        }
        if (selectedPartition != null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Selected: ${selectedPartition.name}", style = MaterialTheme.typography.titleMedium)
                        Text("Readback creates a user-selected backup file from the device partition.")
                        Text("Flashing uses the selected scatter partition and validates the image against partition_size before any write.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onPickImage, enabled = selectedPartition.isDownload) { Text("Select image") }
                        imageName?.let { Text("Image: $it${imageSize?.let { s -> " (${formatBytes(s)})" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onReadback) { Text("Readback") }
                            Button(onClick = onFlash, enabled = selectedPartition.isDownload && imageName != null) { Text("Flash partition") }
                        }
                    }
                }
            }
        }
        item {
            if (operation != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(operation, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
            if (message.isNotBlank()) {
                Divider(Modifier.padding(vertical = 4.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                "Safety: preloader, GPT and protected partitions remain blocked by the partition safety layer. " +
                    "The flash action does not fabricate DA/authentication or bypass device authorization.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.2f GiB".format(value / (1024.0 * 1024.0 * 1024.0))
    value >= 1024L * 1024L -> "%.2f MiB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.2f KiB".format(value / 1024.0)
    else -> "$value B"
}
