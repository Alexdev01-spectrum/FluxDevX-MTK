package com.fluxdevx.mtk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * User-supplied MediaTek authentication material.
 *
 * The file is treated as opaque bytes here. The transport/backend may pass it
 * to a legitimate vendor/Penumbra authentication implementation, but this
 * class never attempts to bypass SLA/DAA or manufacture signatures.
 */
data class AuthFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val bytes: ByteArray,
)

fun loadAuthFile(context: Context, uri: Uri): AuthFile {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val size = if (cursor.isNull(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))) -1L
                else cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                name to size
            } else null
        } ?: (uri.lastPathSegment ?: "auth_sv5.auth" to -1L)

    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Unable to open authentication file")

    require(bytes.isNotEmpty()) { "Authentication file is empty" }
    require(bytes.size <= 16 * 1024 * 1024) { "Authentication file is unexpectedly large" }

    return AuthFile(uri, name.first, if (name.second >= 0) name.second else bytes.size.toLong(), bytes)
}
