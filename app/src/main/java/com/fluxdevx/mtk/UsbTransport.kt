package com.fluxdevx.mtk

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin, blocking bulk transport over Android UsbDeviceConnection.
 * Protocol framing stays outside this class so it can later feed Penumbra.
 */
class UsbBulkTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val timeoutMs: Int = 3000,
) : AutoCloseable {
    private var claimed = false

    fun open(): Boolean {
        if (claimed) return true
        claimed = connection.claimInterface(usbInterface, true)
        return claimed
    }

    suspend fun write(data: ByteArray): Int = withContext(Dispatchers.IO) {
        check(claimed) { "USB interface is not claimed" }
        connection.bulkTransfer(outEndpoint, data, data.size, timeoutMs)
    }

    suspend fun read(maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        check(claimed) { "USB interface is not claimed" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        val buffer = ByteArray(maxBytes)
        val count = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMs)
        if (count < 0) throw UsbTransportException("USB bulk read failed ($count)")
        buffer.copyOf(count)
    }

    fun findBulkPair(): Pair<UsbEndpoint, UsbEndpoint>? {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
        }
        return if (input != null && output != null) Pair(input, output) else null
    }

    override fun close() {
        if (claimed) {
            runCatching { connection.releaseInterface(usbInterface) }
            claimed = false
        }
        connection.close()
    }
}

class UsbTransportException(message: String) : Exception(message)
