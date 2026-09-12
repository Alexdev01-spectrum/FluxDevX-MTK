package com.fluxdevx.mtk

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/** JNI boundary for the Penumbra-backed MediaTek DA partition backend. */
object NativeBridge {
    init { System.loadLibrary("fluxdevx_mtk_bridge") }

    external fun nativeReadPartition(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        da: ByteArray,
        auth: ByteArray,
        mode: Int,
        partition: String,
        outputPath: String,
    ): ByteArray

    external fun nativeWritePartition(
        connection: UsbDeviceConnection,
        inEndpoint: UsbEndpoint,
        outEndpoint: UsbEndpoint,
        da: ByteArray,
        auth: ByteArray,
        mode: Int,
        partition: String,
        imagePath: String,
    ): ByteArray
}
