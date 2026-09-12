package com.fluxdevx.mtk

import java.io.ByteArrayOutputStream

/** Read-only BROM/Preloader identification protocol. */
class MtkProtocol(private val transport: UsbBulkTransport) {
    companion object {
        private val HANDSHAKE = byteArrayOf(0xA0.toByte(), 0x0A, 0x50, 0x05)
        private const val GET_HW_CODE = 0xFD
        private const val GET_HW_SW_VER = 0xFC
        private const val GET_SOC_ID = 0xE7
        private const val GET_MEID = 0xE1
        private const val GET_TARGET_CONFIG = 0xD8
        private const val GET_PL_CAPABILITIES = 0xFB
    }

    data class Identity(
        val hwCode: Int,
        val hwVersion: IntArray,
        val socId: ByteArray,
        val meid: ByteArray,
        val targetConfig: Long,
        val plCapabilities: LongArray,
    )

    suspend fun handshake(retries: Int = 5) {
        check(transport.open()) { "Unable to claim MediaTek USB interface" }
        var last: Throwable? = null
        repeat(retries) {
            try {
                for (byte in HANDSHAKE) {
                    transport.writeFully(byteArrayOf(byte))
                    val response = transport.readExact(1)[0].toInt() and 0xFF
                    val expected = (byte.toInt() and 0xFF) xor 0xFF
                    if (response != expected && response != (HANDSHAKE[0].toInt() and 0xFF)) {
                        error("Handshake mismatch: got 0x%02X expected 0x%02X".format(response, expected))
                    }
                }
                return
            } catch (t: Throwable) {
                last = t
            }
        }
        throw UsbTransportException("MediaTek handshake failed after $retries attempts: ${last?.message}")
    }

    suspend fun identify(): Identity = Identity(
        hwCode = commandU16(GET_HW_CODE),
        hwVersion = commandU16x3(GET_HW_SW_VER),
        socId = commandBytes(GET_SOC_ID),
        meid = commandBytes(GET_MEID),
        targetConfig = commandU32(GET_TARGET_CONFIG),
        plCapabilities = longArrayOf(commandU32(GET_PL_CAPABILITIES), commandU32(GET_PL_CAPABILITIES)),
    )

    private suspend fun commandU16(command: Int): Int {
        transport.writeFully(byteArrayOf(command.toByte()))
        return transport.readU16()
    }

    private suspend fun commandU16x3(command: Int): IntArray {
        transport.writeFully(byteArrayOf(command.toByte()))
        return intArrayOf(transport.readU16(), transport.readU16(), transport.readU16())
    }

    private suspend fun commandU32(command: Int): Long {
        transport.writeFully(byteArrayOf(command.toByte()))
        return transport.readU32()
    }

    private suspend fun commandBytes(command: Int): ByteArray {
        transport.writeFully(byteArrayOf(command.toByte()))
        val length = transport.readU32().toInt()
        require(length in 0..1024 * 1024) { "Invalid device response length: $length" }
        return transport.readExact(length)
    }
}

suspend fun UsbBulkTransport.writeFully(data: ByteArray) {
    var offset = 0
    while (offset < data.size) {
        val written = write(data.copyOfRange(offset, data.size))
        if (written <= 0) throw UsbTransportException("USB bulk write failed ($written)")
        offset += written
    }
}

suspend fun UsbBulkTransport.readExact(size: Int): ByteArray {
    require(size >= 0)
    val output = ByteArrayOutputStream(size)
    while (output.size() < size) {
        val chunk = read(size - output.size())
        if (chunk.isEmpty()) throw UsbTransportException("USB bulk read returned no data")
        output.write(chunk)
    }
    return output.toByteArray()
}

suspend fun UsbBulkTransport.readU16(): Int {
    val b = readExact(2)
    return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
}

suspend fun UsbBulkTransport.readU32(): Long {
    val b = readExact(4)
    return ((b[0].toLong() and 0xFF) shl 24) or
        ((b[1].toLong() and 0xFF) shl 16) or
        ((b[2].toLong() and 0xFF) shl 8) or
        (b[3].toLong() and 0xFF)
}
