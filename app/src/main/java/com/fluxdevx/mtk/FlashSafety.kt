package com.fluxdevx.mtk

import java.io.File

/** Planning/validation layer for destructive partition operations. */
object FlashSafety {
    private val protected = setOf("preloader", "preloader_backup", "pgpt", "sgpt")

    data class WritePlan(
        val partition: GptPartition,
        val image: File,
        val sectorSize: Long = 512,
    ) {
        val capacityBytes: Long get() = partition.sectorCount * sectorSize
    }

    fun validateWrite(partition: GptPartition, image: File, sectorSize: Long = 512): WritePlan {
        require(sectorSize > 0) { "Invalid sector size" }
        require(image.exists() && image.isFile) { "Image does not exist" }
        require(image.length() > 0) { "Image is empty" }
        val name = partition.name.lowercase()
        require(name !in protected) { "Refusing direct write to protected partition '$name'" }
        val capacity = Math.multiplyExact(partition.sectorCount, sectorSize)
        require(image.length() <= capacity) {
            "Image is ${image.length()} bytes but '$name' only has $capacity bytes"
        }
        return WritePlan(partition, image, sectorSize)
    }

    fun validateErase(partition: GptPartition) {
        val name = partition.name.lowercase()
        require(name !in protected) { "Refusing erase of protected partition '$name'" }
    }

    fun requireExplicitConfirmation(confirmed: Boolean) {
        check(confirmed) { "Destructive operation requires explicit confirmation" }
    }
}
