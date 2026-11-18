package com.linguareader.shared.update

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativePatchArchiveTest {
    private fun archive(entries: Map<String,ByteArray>, action: (File)->Unit) {
        val file = File.createTempFile("hotfix-archive", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { z -> entries.forEach { (path, bytes) ->
                z.putNextEntry(ZipEntry(path));z.write(bytes);z.closeEntry()
            } }
            action(file)
        } finally { file.delete() }
    }
    @Test fun normalMetadataIsAcceptedForLaterSignatureValidation() {
        archive(mapOf("assets/package_meta.txt" to "TINKER_ID=reader-25".toByteArray())) { NativePatchArchive.validate(it) }
    }
    @Test fun refusesUncompressedSizeBombEvenWhenZipIsSmall() {
        archive(mapOf("assets/package_meta.txt" to ByteArray(10000))) { file ->
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file,maxBytes=1000) }
        }
    }
    @Test fun refusesTraversalAndExcessiveEntries() {
        archive(mapOf("../evil" to ByteArray(1))) { file ->
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file) }
        }
        archive(mapOf("one" to ByteArray(1),"two" to ByteArray(1))) { file ->
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file,maxEntries=1) }
        }
    }
    @Test fun rejectsDuplicatePaths() {
        archive(mapOf("assets/package_meta.txt" to ByteArray(1), "assets/package_meto.txt" to ByteArray(1))) { file ->
            file.writeBytes(file.readBytes().toString(Charsets.ISO_8859_1).replace("package_meto", "package_meta").toByteArray(Charsets.ISO_8859_1))
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file) }
        }
    }
    @Test fun requiresPackageMetadataAndValidZip() {
        archive(mapOf("arbitrary" to ByteArray(1))) { file ->
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file) }
            file.writeText("not a ZIP")
            assertThrows(IllegalArgumentException::class.java) { NativePatchArchive.validate(file) }
        }
    }
}
