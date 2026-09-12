package com.fluxdevx.mtk

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.security.MessageDigest

class MtkSession(private val usbManager: UsbManager) {
    suspend fun identify(device: UsbDevice): MtkProtocol.Identity {
        check(usbManager.hasPermission(device)) { "Android USB permission is required" }
        val connection = usbManager.openDevice(device) ?: error("Unable to open ${device.deviceName}")
        var transport: UsbBulkTransport? = null
        try {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var input = (0 until iface.endpointCount)
                    .map(iface::getEndpoint)
                    .firstOrNull { it.type == 2 && it.direction == 0x80 }
                var output = (0 until iface.endpointCount)
                    .map(iface::getEndpoint)
                    .firstOrNull { it.type == 2 && it.direction == 0 }
                if (input != null && output != null) {
                    transport = UsbBulkTransport(connection, iface, input, output)
                    break
                }
            }
            val selected = transport ?: error("No bulk IN/OUT endpoint pair found")
            selected.open()
            return MtkProtocol(selected).run {
                handshake()
                identify()
            }
        } finally {
            transport?.close() ?: connection.close()
        }
    }
}

data class AuthMaterial(
    val displayName: String,
    val size: Long,
    val sha256: String,
)

fun AuthFile.toAuthMaterial(): AuthMaterial = AuthMaterial(
    displayName = displayName,
    size = sizeBytes,
    sha256 = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) },
)
