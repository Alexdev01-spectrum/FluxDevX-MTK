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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val ACTION_USB_PERMISSION = "com.fluxdevx.mtk.USB_PERMISSION"
private const val MTK_VID = 0x0E8D

data class MtkUsbDevice(val device: UsbDevice, val mode: String, val permissionGranted: Boolean)
data class UsbProbeResult(val interfaceNumber: Int, val interfaceClass: Int, val endpoints: List<String>)

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private var refresh by mutableIntStateOf(0)
    private var status by mutableStateOf("Waiting for a MediaTek USB device")
    private var probeResults by mutableStateOf<Map<Int, List<UsbProbeResult>>>(emptyMap())

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
                FluxDevXScreen(remember(refresh) { scanMtkDevices() }, status, probeResults, ::requestPermission, ::probeDevice)
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

    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
}

@Composable
private fun FluxDevXScreen(
    devices: List<MtkUsbDevice>, status: String,
    probes: Map<Int, List<UsbProbeResult>>,
    onRequestPermission: (MtkUsbDevice) -> Unit,
    onProbe: (MtkUsbDevice) -> Unit
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
            item { Text("Phase 0.2: Android USB enumeration, permission, open/close, and endpoint inspection. No flashing or erase operations.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
