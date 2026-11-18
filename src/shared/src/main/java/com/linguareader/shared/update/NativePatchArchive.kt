package com.linguareader.shared.update

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipException

/** Bound unsigned archive work before handing bytes to the native patch signature verifier. */
object NativePatchArchive {
    fun validate(file: File, maxBytes: Long = 256L * 1024 * 1024, maxEntries: Int = 256) {
        try {
            ZipFile(file).use { archive ->
                val seen = HashSet<String>()
                var total = 0L
                val entries = archive.entries()
                val buffer = ByteArray(32 * 1024)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(seen.add(entry.name) && seen.size <= maxEntries) { "Duplicate or excessive patch entries" }
                    require(!entry.name.startsWith('/') && '\\' !in entry.name && '\u0000' !in entry.name &&
                        entry.name.split('/').none { it == ".." || it == "." } && ':' !in entry.name) { "Unsafe patch entry" }
                    require(entry.size <= maxBytes)
                    if (!entry.isDirectory) archive.getInputStream(entry).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= maxBytes) { "Expanded patch exceeds size limit" }
                        }
                    }
                }
                require(seen.contains("assets/package_meta.txt")) { "Missing signed package metadata" }
            }
        } catch (e: ZipException) { throw IllegalArgumentException("Malformed hotfix archive", e) }
    }
}
