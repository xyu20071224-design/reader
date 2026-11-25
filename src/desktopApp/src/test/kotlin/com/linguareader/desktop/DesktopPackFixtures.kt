package com.linguareader.desktop

import com.linguareader.shared.packs.*
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DesktopPackFixtures {
    fun database(root: File, meaning: String = "默认释义", validSchema: Boolean = true): File {
        val file = File(root, "dictionary-${UUID.randomUUID()}.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE entries(word TEXT PRIMARY KEY, phonetic TEXT, translation TEXT, definition TEXT)")
                if (validSchema) it.execute("CREATE TABLE forms(form TEXT, lemma TEXT)")
            }
            connection.prepareStatement("INSERT INTO entries VALUES ('apple','','n. ' || ?,'a fruit')").use {
                it.setString(1, meaning); it.executeUpdate()
            }
            if (validSchema) connection.createStatement().use { it.execute("INSERT INTO forms VALUES('apples','apple')") }
        }
        return file
    }

    fun dictionary(root: File, id: String = "test-dictionary", version: String = "1.0.0",
        meaning: String = "包内释义", validSchema: Boolean = true, minimum: Int = 0,
        badHash: Boolean = false, extra: Map<String, ByteArray> = emptyMap()): File {
        val bytes = database(root, meaning, validSchema).readBytes()
        val manifest = PackManifest(id, PackType.DICTIONARY, version, id, id, 1, minimum,
            listOf(PackFileEntry("dictionary.sqlite", if (badHash) "0".repeat(64) else sha(bytes), bytes.size.toLong())),
            PackPayload.Dictionary("dictionary.sqlite", 1, "test fixture", "test"))
        return archive(root, manifest, mapOf("dictionary.sqlite" to bytes) + extra)
    }

    fun bundle(root: File, members: List<Pair<String, File>>, declaredId: String? = null): File {
        val bytes = members.associate { (id, file) -> "$id.lrpack" to file.readBytes() }
        val manifest = PackManifest("test-bundle", PackType.BUNDLE, "1.0.0", "集合", "bundle", 1, 0,
            bytes.map { (path, value) -> PackFileEntry(path, sha(value), value.size.toLong()) },
            PackPayload.Bundle(members.map { (id, _) ->
                BundleMember(PackType.DICTIONARY, declaredId ?: id, "$id.lrpack", "1.0.0")
            }))
        return archive(root, manifest, bytes)
    }

    fun archive(root: File, manifest: PackManifest, files: Map<String, ByteArray>): File {
        val target = File(root, "fixture-${UUID.randomUUID()}.lrpack")
        ZipOutputStream(target.outputStream()).use { zip ->
            (mapOf(PackManifest.FILE_NAME to manifest.toJson().toByteArray()) + files).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return target
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
