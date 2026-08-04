package com.ventoid.app.formatter

import com.ventoid.app.installer.ExFatFormatter
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32

enum class FormatFileSystem(val displayName: String) {
    FAT16("FAT16"),
    FAT32("FAT32"),
    EXFAT("EXFAT"),
}

enum class FormatPartitionTable(val displayName: String) {
    MBR("MBR"),
    GPT("GPT"),
}

object FormatClusterSize {
    const val DEFAULT = "Default Value"
    val OPTIONS = listOf(
        DEFAULT,
        "512 B",
        "1024 B",
        "2048 B",
        "4096 B",
        "8192 B",
        "16 KB",
        "32 KB",
        "64 KB",
    )

    fun parseSectorsPerCluster(choice: String, defaultSectors: Int): Int {
        return when (choice) {
            "512 B" -> 1
            "1024 B" -> 2
            "2048 B" -> 4
            "4096 B" -> 8
            "8192 B" -> 16
            "16 KB" -> 32
            "32 KB" -> 64
            "64 KB" -> 128
            else -> defaultSectors
        }
    }
}

class UsbFormatter(
    private val blockDevice: BlockDeviceDriver,
) {
    private val blockSize: Int get() = blockDevice.blockSize
    private val totalBlocks: Long get() = blockDevice.blocks

    init {
        require(blockSize == 512) { "UsbFormatter requires 512-byte sector size, got $blockSize" }
    }

    @Throws(IOException::class)
    fun format(
        fileSystem: FormatFileSystem,
        partitionTable: FormatPartitionTable,
        clusterSizeChoice: String,
        volumeLabel: String,
        onProgress: ((message: String) -> Unit)? = null,
    ) {
        val label = volumeLabel.trim().ifEmpty { "USB_DRIVE" }
        onProgress?.invoke("Preparing drive layout for ${partitionTable.displayName}...")

        // libums reports `blocks` as the last block LBA, not a 1-based count.
        // The real number of 512-byte blocks is `blocks + 1`. Using it keeps the
        // partition size and every filesystem size field exactly aligned with the
        // capacity the device controller actually exposed.
        val actualBlockCount = (totalBlocks + 1).coerceAtLeast(0L)
        onProgress?.invoke("Device capacity detected: ${formatBytes(actualBlockCount * blockSize)}")

        val useGpt = (partitionTable == FormatPartitionTable.GPT)
        val partStartSector = 2048L
        val gptOverhead = if (useGpt) 34L else 0L
        val partSectorCount = (actualBlockCount - partStartSector - gptOverhead).coerceAtLeast(2048L)

        if (useGpt) {
            onProgress?.invoke("Writing GPT partition table...")
            writeGptTable(partStartSector, partSectorCount, fileSystem, label)
        } else {
            onProgress?.invoke("Writing MBR partition table...")
            writeMbrTable(partStartSector, partSectorCount, fileSystem)
        }

        onProgress?.invoke("Formatting partition as ${fileSystem.displayName}...")
        when (fileSystem) {
            FormatFileSystem.EXFAT -> formatExFat(partStartSector, partSectorCount, clusterSizeChoice, label)
            FormatFileSystem.FAT32 -> formatFat32(partStartSector, partSectorCount, clusterSizeChoice, label)
            FormatFileSystem.FAT16 -> formatFat16(partStartSector, partSectorCount, clusterSizeChoice, label)
        }

        onProgress?.invoke("Verifying partition format...")
        val verifySector = readSector(partStartSector)
        if (verifySector.size < 512) {
            throw IOException("Format verification failed: sector read error.")
        }
        onProgress?.invoke("Formatting complete!")
    }

    private fun writeMbrTable(partStartSector: Long, partSectorCount: Long, fileSystem: FormatFileSystem) {
        val mbr = ByteArray(512)
        val partitionType: Byte = when (fileSystem) {
            FormatFileSystem.FAT16 -> 0x0E.toByte()
            FormatFileSystem.FAT32 -> 0x0C.toByte()
            FormatFileSystem.EXFAT -> 0x07.toByte()
        }

        val offset = 446
        mbr[offset] = 0x80.toByte()
        mbr[offset + 1] = 0x20.toByte()
        mbr[offset + 2] = 0x21.toByte()
        mbr[offset + 3] = 0x00.toByte()
        mbr[offset + 4] = partitionType
        mbr[offset + 5] = 0xFE.toByte()
        mbr[offset + 6] = 0xFF.toByte()
        mbr[offset + 7] = 0xFF.toByte()

        val start = partStartSector.toInt()
        val count = partSectorCount.coerceAtMost(0x7FFFFFFF).toInt()
        writeLeInt(mbr, offset + 8, start)
        writeLeInt(mbr, offset + 12, count)

        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        writeSectors(0, mbr)
    }

    private fun writeGptTable(
        partStartSector: Long,
        partSectorCount: Long,
        fileSystem: FormatFileSystem,
        label: String,
    ) {
        val protectiveMbr = ByteArray(512)
        protectiveMbr[446 + 4] = 0xEE.toByte()
        writeLeInt(protectiveMbr, 446 + 8, 1)
        writeLeInt(protectiveMbr, 446 + 12, (totalBlocks - 1).coerceAtMost(0xFFFFFFFFL).toInt())
        protectiveMbr[510] = 0x55.toByte()
        protectiveMbr[511] = 0xAA.toByte()

        val diskGuid = UUID.nameUUIDFromBytes("format_disk:$totalBlocks".toByteArray(StandardCharsets.UTF_8))
        val partGuid = UUID.nameUUIDFromBytes("format_part:$partStartSector".toByteArray(StandardCharsets.UTF_8))
        val typeGuid = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")

        val entries = ByteArray(128 * 128)
        val le = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        putGuidLe(le, 0, typeGuid)
        putGuidLe(le, 16, partGuid)
        le.putLong(32, partStartSector)
        le.putLong(40, partStartSector + partSectorCount - 1)
        val labelBytes = label.toByteArray(StandardCharsets.UTF_16LE)
        labelBytes.copyInto(entries, 56, 0, minOf(labelBytes.size, 72))

        val entriesCrc = crc32(entries)
        val primaryHeader = buildGptHeader(1L, totalBlocks - 1, 34L, totalBlocks - 34, diskGuid, 2L, entriesCrc)
        val backupHeader = buildGptHeader(totalBlocks - 1, 1L, 34L, totalBlocks - 34, diskGuid, totalBlocks - 33, entriesCrc)

        writeSectors(0, protectiveMbr)
        writeSectors(1, primaryHeader)
        writeSectors(2, entries)
        writeSectors(totalBlocks - 33, entries)
        writeSectors(totalBlocks - 1, backupHeader)
    }

    private fun formatExFat(startSector: Long, sectorCount: Long, clusterChoice: String, label: String) {
        val defaultSpc = ExFatFormatter.sectorsPerCluster(sectorCount)
        val spc = FormatClusterSize.parseSectorsPerCluster(clusterChoice, defaultSpc)
        val layout = ExFatFormatter.computeVolumeLayout(sectorCount, spc)

        val mainBoot = ExFatFormatter.buildMainBootRegion(startSector, sectorCount, spc, layout.fatLengthSectors, layout.clusterHeapOffsetSectors)
        writeSectors(startSector, mainBoot)

        val backupBoot = ExFatFormatter.buildBackupBootRegion(mainBoot)
        writeSectors(startSector + 12, backupBoot)

        val fat = ExFatFormatter.buildFat(layout.fatLengthSectors, layout)
        writeSectors(startSector + 24, fat)

        val bitmapOffset = layout.clusterHeapOffsetSectors + (layout.bitmapFirstCluster - 2L) * spc
        val upcaseOffset = layout.clusterHeapOffsetSectors + (layout.upcaseFirstCluster - 2L) * spc
        val rootOffset = layout.clusterHeapOffsetSectors + (layout.rootDirFirstCluster - 2L) * spc

        val bitmap = ExFatFormatter.buildAllocationBitmap(spc, layout)
        writeSectors(startSector + bitmapOffset, bitmap)

        val upcase = ExFatFormatter.buildUpcaseTable(spc, layout.upcaseClusterCount)
        writeSectors(startSector + upcaseOffset, upcase)

        val root = buildExFatRootDir(spc, layout, label)
        writeSectors(startSector + rootOffset, root)
    }

    private fun buildExFatRootDir(spc: Int, layout: ExFatFormatter.VolumeLayout, label: String): ByteArray {
        val root = ExFatFormatter.buildRootDirectoryCluster(spc, layout)
        // exFAT Volume Label supports up to 15 UTF-16LE characters (not 11)
        val cleanLabel = label.take(15).uppercase()
        val utf16 = cleanLabel.toByteArray(Charsets.UTF_16LE)
        root[65] = cleanLabel.length.toByte()
        utf16.copyInto(root, 66, 0, minOf(30, utf16.size))
        return root
    }

    private fun formatFat32(startSector: Long, sectorCount: Long, clusterChoice: String, label: String) {
        val spc = FormatClusterSize.parseSectorsPerCluster(clusterChoice, 8)
        val reservedSectors = 32
        val numFats = 2

        // Iteratively compute FAT size and cluster count together to avoid the
        // chicken-and-egg problem (cluster count depends on FAT size and vice versa).
        // One pass is sufficient: over-estimate clusters first, derive FAT size,
        // then compute the actual cluster count from the remaining data sectors.
        val fatSizeSectors = run {
            val maxClusters = ((sectorCount - reservedSectors) / spc).toInt()
            (((maxClusters + 2) * 4L + 511) / 512).toInt()
        }
        val dataSectors = sectorCount - reservedSectors - numFats.toLong() * fatSizeSectors
        val totalClusters = (dataSectors / spc).toInt()

        val bootSector = ByteArray(512)
        val le = ByteBuffer.wrap(bootSector).order(ByteOrder.LITTLE_ENDIAN)

        bootSector[0] = 0xEB.toByte()
        bootSector[1] = 0x58.toByte()
        bootSector[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 3)

        le.putShort(11, 512)                    // Bytes per sector
        bootSector[13] = spc.toByte()           // Sectors per cluster
        le.putShort(14, reservedSectors.toShort()) // Reserved sectors
        bootSector[16] = numFats.toByte()       // Number of FATs
        le.putShort(17, 0)                      // Root entries (FAT32 = 0)
        le.putShort(19, 0)                      // Total sectors 16-bit (FAT32 = 0)
        bootSector[21] = 0xF8.toByte()          // Media descriptor
        le.putShort(22, 0)                      // Sectors per FAT 16-bit (FAT32 = 0)
        le.putShort(24, 63)                     // Sectors per track
        le.putShort(26, 255)                    // Number of heads
        le.putInt(28, startSector.toInt())      // Hidden sectors (LBA of partition start)
        le.putInt(32, sectorCount.toInt())      // Total sectors 32-bit

        // FAT32 extended BPB
        le.putInt(36, fatSizeSectors)           // Sectors per FAT (32-bit)
        le.putShort(40, 0)                      // Ext flags
        le.putShort(42, 0)                      // FS version
        le.putInt(44, 2)                        // Root cluster (cluster 2)
        le.putShort(48, 1)                      // FS Info sector number
        le.putShort(50, 6)                      // Backup boot sector

        // Extended boot record
        bootSector[64] = 0x80.toByte()          // Drive number
        bootSector[66] = 0x29.toByte()          // Extended boot signature
        le.putInt(67, 0x12345678)               // Volume serial number
        val formattedLabel = label.padEnd(11, ' ').take(11).uppercase()
        formattedLabel.toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 71)
        "FAT32   ".toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 82)

        bootSector[510] = 0x55.toByte()
        bootSector[511] = 0xAA.toByte()

        writeSectors(startSector, bootSector)       // Primary boot sector (LBA 0 of partition)
        writeSectors(startSector + 6, bootSector)   // Backup boot sector (LBA 6)

        // FSInfo sector (LBA 1 of partition)
        val fsInfo = ByteArray(512)
        val leFs = ByteBuffer.wrap(fsInfo).order(ByteOrder.LITTLE_ENDIAN)
        leFs.putInt(0, 0x41615252)                              // Lead signature
        leFs.putInt(484, 0x61417272)                            // Structure signature
        leFs.putInt(488, (totalClusters - 1).coerceAtLeast(0)) // Free cluster count
        leFs.putInt(492, 3)                                     // Next free cluster
        leFs.putInt(508, 0xAA550000.toInt())                    // Trail signature
        fsInfo[510] = 0x55.toByte()
        fsInfo[511] = 0xAA.toByte()
        writeSectors(startSector + 1, fsInfo)
        writeSectors(startSector + 7, fsInfo)   // Backup FSInfo at LBA 7

        // FAT tables — FAT[0]=media descriptor, FAT[1]=EOC, FAT[2]=EOC (root cluster)
        val fatBytes = fatSizeSectors * 512
        val fat = ByteArray(fatBytes)
        val leFat = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        leFat.putInt(0, 0x0FFFFFF8.toInt())  // FAT[0]: media descriptor (0xF8) padded
        leFat.putInt(4, 0xFFFFFFFF.toInt())  // FAT[1]: EOC
        leFat.putInt(8, 0x0FFFFFFF.toInt())  // FAT[2]: root dir cluster EOC

        writeSectors(startSector + reservedSectors, fat)                           // FAT1
        writeSectors(startSector + reservedSectors + fatSizeSectors, fat)          // FAT2

        // Root directory cluster (cluster 2)
        val rootClusterSector = startSector + reservedSectors + numFats.toLong() * fatSizeSectors
        val rootCluster = ByteArray(spc * 512)
        formattedLabel.toByteArray(StandardCharsets.US_ASCII).copyInto(rootCluster, 0)
        rootCluster[11] = 0x08.toByte()     // Attribute: Volume Label
        writeSectors(rootClusterSector, rootCluster)
    }

    private fun formatFat16(startSector: Long, sectorCount: Long, clusterChoice: String, label: String) {
        val spc = FormatClusterSize.parseSectorsPerCluster(clusterChoice, 16)
        val reservedSectors = 1
        val numFats = 2
        val rootEntries = 512

        val rootDirSectors = (rootEntries * 32 + 511) / 512

        // Iterative/correct FAT16 size: compute FAT size first from a max-cluster estimate,
        // then subtract the actual FAT space to get the true data sector count.
        val fatSizeSectors = run {
            val maxData = sectorCount - reservedSectors - rootDirSectors
            val maxClusters = (maxData / spc).toInt()
            (((maxClusters + 2) * 2 + 511) / 512).toInt()
        }
        val dataSectors = sectorCount - reservedSectors - rootDirSectors - numFats.toLong() * fatSizeSectors
        val totalClusters = (dataSectors / spc).toInt()

        val bootSector = ByteArray(512)
        val le = ByteBuffer.wrap(bootSector).order(ByteOrder.LITTLE_ENDIAN)
        bootSector[0] = 0xEB.toByte()
        bootSector[1] = 0x3C.toByte()
        bootSector[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 3)

        le.putShort(11, 512)                        // Bytes per sector
        bootSector[13] = spc.toByte()               // Sectors per cluster
        le.putShort(14, reservedSectors.toShort())  // Reserved sectors
        bootSector[16] = numFats.toByte()           // Number of FATs
        le.putShort(17, rootEntries.toShort())      // Root entry count

        // Total sectors: use 16-bit field if it fits, else 32-bit field
        val sectorCount16 = if (sectorCount < 65536) sectorCount.toInt() else 0
        le.putShort(19, sectorCount16.toShort())    // Total sectors 16-bit
        bootSector[21] = 0xF8.toByte()              // Media descriptor
        le.putShort(22, fatSizeSectors.toShort())   // Sectors per FAT
        le.putShort(24, 63)                         // Sectors per track
        le.putShort(26, 255)                        // Number of heads
        le.putInt(28, startSector.toInt())          // Hidden sectors

        if (sectorCount16 == 0) {
            le.putInt(32, sectorCount.toInt())      // Total sectors 32-bit (large volumes)
        }

        // Extended BPB
        bootSector[36] = 0x80.toByte()              // Drive number (0x80 = first HDD)
        bootSector[37] = 0x00.toByte()              // Reserved1
        bootSector[38] = 0x29.toByte()              // Extended boot signature
        le.putInt(39, 0x12345678)                   // Volume serial number
        val formattedLabel = label.padEnd(11, ' ').take(11).uppercase()
        formattedLabel.toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 43)
        "FAT16   ".toByteArray(StandardCharsets.US_ASCII).copyInto(bootSector, 54)

        bootSector[510] = 0x55.toByte()
        bootSector[511] = 0xAA.toByte()

        writeSectors(startSector, bootSector)

        // FAT tables — FAT[0]=media descriptor EOC, FAT[1]=EOC
        val fatBytes = fatSizeSectors * 512
        val fat = ByteArray(fatBytes)
        val leFat = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        leFat.putShort(0, 0xFFF8.toShort())  // FAT[0]: 0xFFF8 (media=0xF8, padded)
        leFat.putShort(2, 0xFFFF.toShort())  // FAT[1]: EOC

        writeSectors(startSector + reservedSectors, fat)                              // FAT1
        writeSectors(startSector + reservedSectors + fatSizeSectors, fat)             // FAT2

        // Root directory
        val rootDirStart = startSector + reservedSectors + numFats.toLong() * fatSizeSectors
        val rootDir = ByteArray(rootDirSectors * 512)
        formattedLabel.toByteArray(StandardCharsets.US_ASCII).copyInto(rootDir, 0)
        rootDir[11] = 0x08.toByte()  // Attribute: Volume Label
        writeSectors(rootDirStart, rootDir)
    }

    @Throws(IOException::class)
    private fun readSector(blockOffset: Long): ByteArray {
        val buf = ByteBuffer.allocate(blockSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        blockDevice.read(blockOffset, buf)
        buf.flip()
        val arr = ByteArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    @Throws(IOException::class)
    private fun writeSectors(blockOffset: Long, data: ByteArray) {
        val chunkSectors = 256
        val chunkBytes = blockSize * chunkSectors
        var offset = blockOffset
        var pos = 0
        var remaining = data.size
        val buf = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (remaining > 0) {
            val toWrite = minOf(chunkBytes, remaining)
            buf.clear()
            buf.put(data, pos, toWrite)
            buf.flip()
            blockDevice.write(offset, buf)
            offset += toWrite / blockSize
            pos += toWrite
            remaining -= toWrite
        }
    }

    private fun buildGptHeader(
        currentLba: Long,
        backupLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partitionEntryLba: Long,
        partitionEntryArrayCrc32: Long,
    ): ByteArray {
        val header = ByteArray(512)
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        "EFI PART".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 0)
        le.putInt(8, 0x00010000)
        le.putInt(12, 92)
        le.putLong(24, currentLba)
        le.putLong(32, backupLba)
        le.putLong(40, firstUsableLba)
        le.putLong(48, lastUsableLba)
        putGuidLe(le, 56, diskGuid)
        le.putLong(72, partitionEntryLba)
        le.putInt(80, 128)
        le.putInt(84, 128)
        le.putInt(88, partitionEntryArrayCrc32.toInt())
        le.putInt(16, crc32(header, 0, 92).toInt())
        return header
    }

    private fun putGuidLe(buffer: ByteBuffer, offset: Int, guid: UUID) {
        buffer.putInt(offset, (guid.mostSignificantBits ushr 32).toInt())
        buffer.putShort(offset + 4, (guid.mostSignificantBits ushr 16).toShort())
        buffer.putShort(offset + 6, guid.mostSignificantBits.toShort())
        val lsb = guid.leastSignificantBits
        for (index in 0 until 8) {
            buffer.put(offset + 8 + index, (lsb ushr (56 - index * 8)).toByte())
        }
    }

    private fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        val divisor = when {
            value >= 1L shl 30 -> 1L shl 30
            value >= 1L shl 20 -> 1L shl 20
            value >= 1L shl 10 -> 1L shl 10
            else -> 1L
        }
        val suffix = when {
            divisor == 1L shl 30 -> "GiB"
            divisor == 1L shl 20 -> "MiB"
            divisor == 1L shl 10 -> "KiB"
            else -> "B"
        }
        val amount = if (divisor > 0) value.toDouble() / divisor else value.toDouble()
        return String.format(Locale.US, "%.2f %s (%d blocks x %d B)", amount, suffix, value / blockSize, blockSize)
    }

    private fun writeLeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
