package com.fluxdevx.mtk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** User-supplied Download Agent. No patching or bypass transformation is performed. */
data class DaFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

fun loadDaFile(context: Context, uri: Uri): DaFile {
    val resolver = context.contentResolver
    val meta = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val sizeIndex = c.getColumnIndexOrThrow(OpenableColumns.SIZE)
                name to if (c.isNull(sizeIndex)) -1L else c.getLong(sizeIndex)
            } else null
        } ?: ((uri.lastPathSegment ?: "DA.bin") to -1L)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Unable to open DA file")
    require(bytes.isNotEmpty()) { "DA file is empty" }
    require(bytes.size <= 128 * 1024 * 1024) { "DA file is unexpectedly large" }
    return DaFile(uri, meta.first, if (meta.second >= 0) meta.second else bytes.size.toLong(), bytes)
}
