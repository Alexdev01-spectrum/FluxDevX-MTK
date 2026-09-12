package com.fluxdevx.mtk

/** Explicit operation model used by the UI/backend boundary. */
sealed interface PartitionOperation {
    data class Readback(val partition: ScatterPartition, val destination: String) : PartitionOperation
    data class Flash(val partition: ScatterPartition, val image: String) : PartitionOperation
    data class Erase(val partition: ScatterPartition) : PartitionOperation
}

data class PartitionSafetyResult(val allowed: Boolean, val reason: String? = null)

object PartitionSafety {
    private val protected = setOf(
        "preloader", "preloader_backup", "pgpt", "sgpt", "protect1", "protect2"
    )

    fun checkWrite(partition: ScatterPartition): PartitionSafetyResult {
        if (partition.name.lowercase() in protected) {
            return PartitionSafetyResult(false, "${partition.name} is protected from generic flashing")
        }
        if (!partition.isDownload) {
            return PartitionSafetyResult(false, "Scatter marks ${partition.name} as non-downloadable")
        }
        return PartitionSafetyResult(true)
    }

    fun checkErase(partition: ScatterPartition): PartitionSafetyResult {
        if (partition.name.lowercase() in protected) {
            return PartitionSafetyResult(false, "${partition.name} is protected from generic erase")
        }
        return PartitionSafetyResult(true)
    }
}
