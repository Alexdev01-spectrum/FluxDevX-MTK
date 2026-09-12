package com.fluxdevx.mtk

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Partition manager orchestration. It deliberately separates scatter parsing,
 * image validation and the eventual device backend so the UI cannot accidentally
 * write a file to a partition without an explicit operation.
 */
class PartitionManager(private val context: Context) {
    suspend fun readback(
        partition: ScatterPartition,
        output: Uri,
        expectedBytes: Long,
        reader: suspend (offset: Long, length: Int) -> ByteArray,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(expectedBytes > 0) { "Readback size must be positive" }
        val out = context.contentResolver.openOutputStream(output)
            ?: error("Unable to open readback destination")
        out.use { stream ->
            var offset = 0L
            val chunkSize = 1024 * 1024
            while (offset < expectedBytes) {
                val count = minOf(chunkSize.toLong(), expectedBytes - offset).toInt()
                val chunk = reader(offset, count)
                require(chunk.isNotEmpty()) { "Device returned an empty readback chunk at $offset" }
                require(chunk.size <= count) { "Device returned too much data" }
                stream.write(chunk)
                offset += chunk.size
                onProgress(offset, expectedBytes)
            }
        }
    }

    suspend fun flash(
        partition: ScatterPartition,
        image: Uri,
        deviceWrite: suspend (offset: Long, data: ByteArray) -> Unit,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(partition.isDownload) { "Scatter marks ${partition.name} as non-downloadable" }
        val resolver = context.contentResolver
        val size = resolver.query(image, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
        require(size >= 0) { "Unable to determine image size" }
        validateScatterImage(partition, size)

        resolver.openInputStream(image)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            var offset = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                deviceWrite(offset, buffer.copyOf(read))
                offset += read
                onProgress(offset, size)
            }
        } ?: error("Unable to open image")
    }
}
