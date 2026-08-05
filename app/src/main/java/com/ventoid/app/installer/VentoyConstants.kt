package com.ventoid.app.installer

/**
 * Ventoy disk layout constants from VENTOY_C_SOURCE_ANALYSIS.md / Ventoy2Disk.h / ventoy_lib.sh
 */
object VentoyConstants {
    const val SECTOR_SIZE = 512
    const val VENTOY_EFI_PART_SIZE_BYTES = 32 * 1024 * 1024  // 32 MB
    const val VENTOY_SECTOR_NUM = 65536                      // 32MB / 512
    const val PART1_START_SECTOR = 2048L
    const val ALIGNMENT_SECTORS = 8L                         // 4KB alignment

    const val MBR_PART1_TYPE_EXFAT: Int = 0x07
    const val MBR_PART2_TYPE_EFI: Int = 0xEF
    //const val MBR_PART2_TYPE_HIDDEN_FAT16: Int = 0x1C        // Hidden FAT16 partition type (prevents Windows drive letter assignment in MBR)
    const val GPT_PROTECTIVE_MBR_TYPE: Int = 0xEE

    /** GPT Partition Attributes for VTOYEFI: Bit 63 (No Drive Letter), Bit 62 (Hidden), Bit 0 (System Partition) */
    const val GPT_ATTR_SYSTEM_PARTITION: Long = 0x0000000000000001L
    const val GPT_ATTR_HIDDEN: Long = 0x4000000000000000L
    const val GPT_ATTR_NO_DRIVE_LETTER: Long = Long.MIN_VALUE // 0x8000000000000000L (Bit 63)
    const val GPT_VTOYEFI_ATTRIBUTES: Long = GPT_ATTR_NO_DRIVE_LETTER or GPT_ATTR_HIDDEN or GPT_ATTR_SYSTEM_PARTITION

    const val MBR_BOOT_CODE_SIZE = 446
    const val MBR_PARTITION_ENTRY_SIZE = 16
    const val MBR_PARTITION_ENTRIES = 4
    const val MBR_SIGNATURE_55 = 0x55.toByte()
    const val MBR_SIGNATURE_AA = 0xAA.toByte()

    /** core.img: MBR style = 2047 sectors; GPT style = 2014 sectors (written from sector 34) */
    const val CORE_IMG_SECTORS_MBR = 2047
    const val CORE_IMG_SECTORS_GPT = 2014
    const val CORE_IMG_OFFSET_SECTOR_MBR = 1L
    const val CORE_IMG_OFFSET_SECTOR_GPT = 34L
}
