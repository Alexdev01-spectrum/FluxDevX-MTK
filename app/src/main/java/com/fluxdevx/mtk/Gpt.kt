package com.fluxdevx.mtk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class GptPartition(
    val index: Int,
    val name: String,
    val firstLba: Long,
    val lastLba: Long,
    val attributes: Long,
    val typeGuid: UUID,
    val uniqueGuid: UUID,
) {
    val sectorCount: Long get() = lastLba - firstLba + 1
}

/** Parses a standard GPT header plus partition-entry array from a sector image. */
object GptParser {
    private const val SECTOR = 512
    private const val ENTRY_SIZE = 128

    fun parse(image: ByteArray): List<GptPartition> {
        require(image.size >= 2 * SECTOR) { "GPT image is too small" }
        require(String(image, SECTOR, 8, Charsets.US_ASCII) == "EFI PART") { "GPT signature not found" }
        val header = ByteBuffer.wrap(image, SECTOR, SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        header.position(72)
        val firstEntryLba = header.long
        val entryCount = header.int
        val entrySize = header.int
        require(entryCount in 1..16384) { "Invalid GPT entry count: $entryCount" }
        require(entrySize in ENTRY_SIZE..4096) { "Unsupported GPT entry size: $entrySize" }
        val tableOffsetLong = Math.multiplyExact(firstEntryLba, SECTOR.toLong())
        val totalLong = Math.multiplyExact(entryCount.toLong(), entrySize.toLong())
        require(tableOffsetLong <= Int.MAX_VALUE && totalLong <= Int.MAX_VALUE) { "GPT image is too large" }
        val tableOffset = tableOffsetLong.toInt()
        val total = totalLong.toInt()
        require(tableOffset >= 0 && tableOffset <= image.size - total) { "GPT entry table exceeds supplied image" }

        val result = ArrayList<GptPartition>()
        for (i in 0 until entryCount) {
            val base = tableOffset + i * entrySize
            val entry = ByteBuffer.wrap(image, base, entrySize).order(ByteOrder.LITTLE_ENDIAN)
            val type = uuidFromGpt(entry)
            val unique = uuidFromGpt(entry)
            val first = entry.long
            val last = entry.long
            val attributes = entry.long
            val nameBytes = ByteArray(minOf(72, entry.remaining()))
            entry.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000').trim()
            if (type != UUID(0L, 0L) && first <= last) {
                result += GptPartition(i + 1, name, first, last, attributes, type, unique)
            }
        }
        return result
    }

    private fun uuidFromGpt(buffer: ByteBuffer): UUID {
        val a = buffer.int.toLong() and 0xFFFF_FFFFL
        val b = buffer.short.toLong() and 0xFFFF
        val c = buffer.short.toLong() and 0xFFFF
        val first = (a shl 32) or (b shl 16) or c
        val secondBytes = ByteArray(8)
        buffer.get(secondBytes)
        var second = 0L
        for (byte in secondBytes) second = (second shl 8) or (byte.toLong() and 0xFF)
        return UUID(first, second)
    }
}
