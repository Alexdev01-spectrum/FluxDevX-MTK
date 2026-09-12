package com.fluxdevx.mtk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.fluxdevx.mtk.USB_PERMISSION"
private const val MTK_VID = 0x0E8D

data class MtkUsbDevice(val device: UsbDevice, val mode: String, val permissionGranted: Boolean)
data class UsbProbeResult(val interfaceNumber: Int, val interfaceClass: Int, val endpoints: List<String>)

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private var refresh by mutableIntStateOf(0)
    private var status by mutableStateOf("Waiting for a MediaTek USB device")
    private var probeResults by mutableStateOf<Map<Int, List<UsbProbeResult>>>(emptyMap())
    private var authFile by mutableStateOf<AuthFile?>(null)
    private var authError by mutableStateOf<String?>(null)
    private var scatterFile by mutableStateOf<ScatterFile?>(null)
    private var scatterName by mutableStateOf<String?>(null)
    private var selectedPartition by mutableStateOf<ScatterPartition?>(null)
    private var imageUri by mutableStateOf<Uri?>(null)
    private var imageName by mutableStateOf<String?>(null)
    private var imageSize by mutableStateOf<Long?>(null)
    private var operation by mutableStateOf<String?>(null)
    private var operationProgress by mutableFloatStateOf(0f)
    private var operationMessage by mutableStateOf("")

    private val authPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadAuthFile(this, uri)
        }.onSuccess {
            authFile = it
            authError = null
            status = "Loaded ${it.displayName} (${it.sizeBytes} bytes)"
        }.onFailure {
            authError = it.message ?: "Could not load authentication file"
            status = "Authentication file rejected"
        }
    }

    private val scatterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read scatter file")
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && index >= 0) c.getString(index) else null
            } ?: "scatter"
            ScatterParser.parse(bytes, name) to name
        }.onSuccess { (parsed, name) ->
            require(parsed.partitions.isNotEmpty()) { "No partitions were found in the scatter file" }
            scatterFile = parsed
            scatterName = name
            selectedPartition = null
            operationMessage = "Loaded ${parsed.partitions.size} partitions from ${parsed.format} scatter."
        }.onFailure {
            operationMessage = "Scatter error: ${it.message ?: "invalid scatter file"}"
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val size = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst() && index >= 0 && !c.isNull(index)) c.getLong(index) else null
            } ?: contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && index >= 0) c.getString(index) else null
            } ?: "image"
            name to size
        }.onSuccess { (name, size) ->
            imageUri = uri
            imageName = name
            imageSize = size
            operationMessage = "Selected $name${size?.let { " (${it} bytes)" } ?: ""}."
        }.onFailure { operationMessage = "Image error: ${it.message ?: "could not inspect image"}" }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    status = if (granted && device != null) "USB permission granted for ${device.deviceName}" else "USB permission denied"
                    refresh++
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { status = "USB device attached"; refresh++ }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { status = "USB device detached"; refresh++ }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(receiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme {
                FluxDevXScreen(
                    remember(refresh) { scanMtkDevices() }, status, probeResults,
                    authFile, authError,
                    scatterFile, scatterName, selectedPartition, imageName, imageSize,
                    operation, operationProgress, operationMessage,
                    ::requestPermission, ::probeDevice,
                    { authPicker.launch(arrayOf("application/octet-stream", "application/auth", "*/*")) },
                    { scatterPicker.launch(arrayOf("text/plain", "text/xml", "application/xml", "*/*")) },
                    { selectedPartition = it },
                    { imagePicker.launch(arrayOf("application/octet-stream", "image/*", "*/*")) },
                    ::requestReadback,
                    ::requestFlash,
                )
            }
        }
    }

    private fun scanMtkDevices(): List<MtkUsbDevice> = usbManager.deviceList.values
        .filter { it.vendorId == MTK_VID }
        .map { MtkUsbDevice(it, classify(it.productId), usbManager.hasPermission(it)) }

    private fun classify(pid: Int) = when (pid) {
        0x0003 -> "BROM"
        0x6000, 0x2000, 0x20FF, 0x3000 -> "PRELOADER"
        0x2001 -> "DA"
        else -> "MEDIATEK / UNKNOWN"
    }

    private fun requestPermission(item: MtkUsbDevice) {
        if (usbManager.hasPermission(item.device)) { status = "Permission already granted"; refresh++; return }
        val pi = PendingIntent.getBroadcast(
            this, item.device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(item.device, pi)
        status = "Requesting Android USB permission…"
    }

    private fun probeDevice(item: MtkUsbDevice) {
        if (!usbManager.hasPermission(item.device)) { requestPermission(item); return }
        val connection = usbManager.openDevice(item.device)
        if (connection == null) { status = "Android could not open ${item.device.deviceName}"; return }
        try {
            val results = mutableListOf<UsbProbeResult>()
            for (i in 0 until item.device.interfaceCount) {
                val iface: UsbInterface = item.device.getInterface(i)
                val endpoints = mutableListOf<String>()
                for (e in 0 until iface.endpointCount) {
                    val ep: UsbEndpoint = iface.getEndpoint(e)
                    val direction = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
                        else -> "OTHER"
                    }
                    endpoints += "$direction $type 0x%02X max=${ep.maxPacketSize}".format(ep.address)
                }
                results += UsbProbeResult(iface.id, iface.interfaceClass, endpoints)
            }
            probeResults = probeResults + (item.device.deviceId to results)
            status = "Opened and inspected ${item.mode}: ${item.device.deviceName}"
        } finally { connection.close() }
    }

    private fun requestReadback() {
        val partition = selectedPartition ?: return
        operation = "Readback: ${partition.name}"
        operationProgress = 0f
        operationMessage = "Readback is staged through PartitionManager. A live DA read callback is required before bytes are requested from the device."
    }

    private fun requestFlash() {
        val partition = selectedPartition ?: return
        val size = imageSize
        runCatching {
            require(partition.isDownload) { "${partition.name} is marked is_download: false" }
            require(size != null) { "Image size is unavailable" }
            validateScatterImage(partition, size)
        }.onSuccess {
            operation = "Flash: ${partition.name}"
            operationProgress = 0f
            operationMessage = "Validated $imageName against ${partition.name}. The DA write backend is not connected yet; no device write was attempted."
        }.onFailure {
            operation = null
            operationMessage = "Flash blocked: ${it.message}"
        }
    }

    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
}

@Composable
private fun FluxDevXScreen(
    devices: List<MtkUsbDevice>, status: String,
    probes: Map<Int, List<UsbProbeResult>>,
    authFile: AuthFile?, authError: String?,
    scatter: ScatterFile?, scatterName: String?, selectedPartition: ScatterPartition?, imageName: String?, imageSize: Long?,
    operation: String?, operationProgress: Float, operationMessage: String,
    onRequestPermission: (MtkUsbDevice) -> Unit,
    onProbe: (MtkUsbDevice) -> Unit,
    onPickAuth: () -> Unit,
    onPickScatter: () -> Unit,
    onSelectPartition: (ScatterPartition) -> Unit,
    onPickImage: () -> Unit,
    onReadback: () -> Unit,
    onFlash: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Column {
            Text("FluxDevX-MTK")
            Text("Android MediaTek transport lab", style = MaterialTheme.typography.labelSmall)
        } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card { Column(Modifier.padding(18.dp)) {
                Text("USB probe", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(if (devices.isEmpty()) "Connect a MediaTek device through USB OTG." else "${devices.size} MediaTek USB device(s) detected.")
                Spacer(Modifier.height(8.dp)); Text(status, style = MaterialTheme.typography.bodySmall)
            } } }
            item { Card { Column(Modifier.padding(18.dp)) {
                Text("Authentication", style = MaterialTheme.typography.titleMedium)
                Text("Load a manufacturer/vendor-provided auth_sv5.auth file for devices that legitimately require authentication.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPickAuth) { Text("Select auth_sv5.auth") }
                authFile?.let { Text("Loaded: ${it.displayName} • ${it.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall) }
                authError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } } }
            item { Card { Column(Modifier.padding(18.dp)) {
                Text("Scatter partition manager", style = MaterialTheme.typography.titleMedium)
                Text("Load a MediaTek TXT or XML scatter file, select a partition, then choose its image for validated flashing.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPickScatter) { Text("Load TXT / XML scatter") }
                scatterName?.let { Text("Scatter: $it", style = MaterialTheme.typography.bodySmall) }
            } } }
            if (scatter != null) {
                item { Text("${scatter.partitions.size} partitions • ${scatter.format}", style = MaterialTheme.typography.labelLarge) }
                items(scatter.partitions, key = { it.name }) { partition ->
                    Card { Column(Modifier.padding(14.dp)) {
                        Text(partition.name, style = MaterialTheme.typography.titleSmall)
                        Text("${partition.fileName ?: "No image"} • size=${partition.partitionSize ?: "unknown"} bytes • ${if (partition.isDownload) "downloadable" else "not for download"}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onSelectPartition(partition) }) { Text(if (selectedPartition?.name == partition.name) "Selected" else "Select") }
                    } }
                }
            }
            if (selectedPartition != null) {
                item { Card { Column(Modifier.padding(18.dp)) {
                    Text("Selected: ${selectedPartition.name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onPickImage, enabled = selectedPartition.isDownload) { Text("Select image") }
                    imageName?.let { Text("Image: $it${imageSize?.let { s -> " ($s bytes)" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReadback) { Text("Readback") }
                        Button(onClick = onFlash, enabled = selectedPartition.isDownload && imageName != null) { Text("Flash partition") }
                    }
                } } }
            }
            item {
                operation?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                if (operation != null) LinearProgressIndicator(progress = { operationProgress }, Modifier.fillMaxWidth())
                if (operationMessage.isNotBlank()) Text(operationMessage, style = MaterialTheme.typography.bodySmall)
            }
            items(devices, key = { it.device.deviceId }) { item -> Card { Column(Modifier.padding(18.dp)) {
                Text(item.mode, style = MaterialTheme.typography.titleMedium)
                Text("VID 0x%04X  PID 0x%04X".format(item.device.vendorId, item.device.productId))
                Text("Node ${item.device.deviceName}")
                Text(if (item.permissionGranted) "● Permission granted" else "○ Permission required")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!item.permissionGranted) Button(onClick = { onRequestPermission(item) }) { Text("Grant USB") }
                    OutlinedButton(onClick = { onProbe(item) }) { Text("Probe USB") }
                }
                probes[item.device.deviceId]?.let { results ->
                    Spacer(Modifier.height(10.dp))
                    results.forEach { result ->
                        Text("Interface ${result.interfaceNumber} • class ${result.interfaceClass}", style = MaterialTheme.typography.labelLarge)
                        result.endpoints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } } }
            item { Text("Auth files are opaque user-supplied data. Security bypass or forged authentication is not performed by this layer.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
