package com.fluxdevx.mtk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MediaTek scatter model/parser. Supports the common TXT scatter syntax and
 * the XML-style scatter representation used by newer vendor packages.
 * This layer only describes partitions; the transport performs no implicit writes.
 */
data class ScatterPartition(
    val name: String,
    val fileName: String?,
    val linearAddress: Long?,
    val physicalAddress: Long?,
    val partitionSize: Long?,
    val region: String?,
    val isDownload: Boolean,
    val rawAttributes: Map<String, String> = emptyMap(),
)

data class ScatterFile(val format: ScatterFormat, val partitions: List<ScatterPartition>)
enum class ScatterFormat { TXT, XML }

object ScatterParser {
    fun parse(bytes: ByteArray, fileName: String): ScatterFile {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return if (text.trimStart().startsWith("<")) parseXml(text) else parseTxt(text)
    }

    private fun parseTxt(text: String): ScatterFile {
        val blocks = Regex("(?ms)^\\s*partition_index:\\s*(.*?)\\n(?=\\s*partition_index:|\\z)")
            .findAll(text).map { it.groupValues[1] }.toList()
        val parts = blocks.mapNotNull { block ->
            val fields = parseFields(block)
            val name = fields["partition_name"] ?: return@mapNotNull null
            ScatterPartition(
                name = name,
                fileName = fields["file_name"]?.takeUnless { it.equals("NONE", true) },
                linearAddress = number(fields["linear_start_addr"]),
                physicalAddress = number(fields["physical_start_addr"]),
                partitionSize = number(fields["partition_size"]),
                region = fields["region"],
                isDownload = fields["is_download"]?.equals("false", true) != true,
                rawAttributes = fields,
            )
        }
        return ScatterFile(ScatterFormat.TXT, parts)
    }

    private fun parseXml(text: String): ScatterFile {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(text.byteInputStream())
        val nodes = doc.getElementsByTagName("partition")
        val parts = buildList {
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val map = linkedMapOf<String, String>()
                val attrs = node.attributes
                for (j in 0 until attrs.length) map[attrs.item(j).nodeName] = attrs.item(j).nodeValue
                fun child(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
                    (node.childNodes.asList().firstOrNull { it.nodeName.equals(n, true) })?.textContent?.trim()
                }
                val name = map["partition_name"] ?: child("partition_name", "name") ?: return@buildList
                add(ScatterPartition(
                    name = name,
                    fileName = map["file_name"] ?: child("file_name"),
                    linearAddress = number(map["linear_start_addr"] ?: child("linear_start_addr")),
                    physicalAddress = number(map["physical_start_addr"] ?: child("physical_start_addr")),
                    partitionSize = number(map["partition_size"] ?: child("partition_size")),
                    region = map["region"] ?: child("region"),
                    isDownload = (map["is_download"] ?: child("is_download"))?.equals("false", true) != true,
                    rawAttributes = map,
                ))
            }
        }
        return ScatterFile(ScatterFormat.XML, parts)
    }

    private fun parseFields(block: String): Map<String, String> = block.lineSequence()
        .mapNotNull { line ->
            val p = line.indexOf(':')
            if (p <= 0) null else line.substring(0, p).trim() to line.substring(p + 1).trim()
        }.toMap()

    private fun number(value: String?): Long? = value?.trim()?.removePrefix("0x")?.removePrefix("0X")?.let {
        runCatching { if (value.trim().startsWith("0x", true)) it.toLong(16) else it.toLong() }.getOrNull()
    }
}

private fun org.w3c.dom.NodeList.asList(): List<org.w3c.dom.Node> = buildList {
    for (i in 0 until length) add(item(i))
}

/** Ensures a selected image can fit the scatter-described partition. */
fun validateScatterImage(partition: ScatterPartition, imageSize: Long) {
    require(imageSize >= 0) { "Invalid image size" }
    val capacity = partition.partitionSize ?: error("Scatter has no partition_size for ${partition.name}")
    require(imageSize <= capacity) {
        "Image is too large for ${partition.name}: $imageSize > $capacity bytes"
    }
}
