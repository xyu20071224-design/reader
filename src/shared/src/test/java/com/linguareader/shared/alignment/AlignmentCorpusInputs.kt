package com.linguareader.shared.alignment

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Mandatory external inputs for F01–F17. Missing inputs are failures, never successful skips. */
internal object AlignmentCorpusInputs {
    const val MANIFEST = "corpus-manifest.json"
    const val REPO_PROPERTY = "linguareader.repoRoot"
    const val CORPUS_PROPERTY = "linguareader.corpusRoot"
    private const val BLOCKS = "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, section, article, header, footer"
    private val SHA = Regex("[0-9a-f]{64}")

    data class Requirement(val corpus: String, val dictionary: Boolean = true, val golden: Boolean = false)
    val requirements = linkedMapOf(
        "F01" to Requirement("lotr"), "F02" to Requirement("ai-translation"),
        "F03" to Requirement("ai-translation"), "F04" to Requirement("ai-translation"),
        "F05" to Requirement("ai-translation"), "F06" to Requirement("ai-translation"),
        "F07" to Requirement("lotr"), "F08" to Requirement("lotr"), "F09" to Requirement("lotr"),
        "F10" to Requirement("lotr", golden = true), "F11" to Requirement("lotr", golden = true),
        "F12" to Requirement("lotr", dictionary = false), "F13" to Requirement("lotr", dictionary = false),
        "F14" to Requirement("lotr"), "F15" to Requirement("lotr", dictionary = false),
        "F16" to Requirement("lotr"), "F17" to Requirement("lotr")
    )

    data class Loaded(
        val corpusId: String, val version: String,
        val english: List<AlignmentChapter>, val chinese: List<AlignmentChapter>,
        val dictionary: EcdictProvider?, val memory: JSONObject? = null, val golden: JSONObject? = null
    ) {
        fun requireDictionary(): EcdictProvider = checkNotNull(dictionary) { "Dictionary was not requested" }
        fun requireMemory(): JSONObject = checkNotNull(memory) { "AI memory was not requested" }
        fun requireGolden(): JSONObject = checkNotNull(golden) { "Approved golden was not requested" }
    }

    fun repoRoot(
        start: File = File(System.getProperty("user.dir")),
        explicit: String? = System.getProperty(REPO_PROPERTY)
    ): File {
        fun isRoot(dir: File) = File(dir, "src/settings.gradle.kts").isFile &&
            File(dir, "src/shared/src/main/java/com/linguareader/shared/alignment/AlignmentModels.kt").isFile
        if (explicit != null) {
            val root = File(explicit).canonicalFile
            check(explicit.isNotBlank() && isRoot(root)) { "Invalid $REPO_PROPERTY=$explicit; expected repository code markers" }
            return root
        }
        var current: File? = start.canonicalFile
        while (current != null) {
            if (isRoot(current)) return current
            current = current.parentFile
        }
        error("Repository code markers not found from ${start.absolutePath}; set -D$REPO_PROPERTY=<repository>")
    }

    fun requireCase(caseId: String): Loaded {
        val repo = repoRoot()
        val explicit = System.getProperty(CORPUS_PROPERTY)
        check(explicit == null || explicit.isNotBlank()) { "Empty $CORPUS_PROPERTY" }
        val root = explicit?.let(::File) ?: File(repo, "artifacts/alignment-corpus")
        return load(root, caseId)
    }

    /** Public to test support, so fixture tests never depend on process properties or private books. */
    fun load(root: File, caseId: String, report: (String) -> Unit = ::println): Loaded {
        val requirement = requireNotNull(requirements[caseId]) { "Unknown corpus case $caseId" }
        val context = "$caseId corpus=${requirement.corpus}"
        val manifestFile = File(root, MANIFEST)
        check(manifestFile.isFile) {
            "$context: missing $manifestFile; required=${dependencies(requirement)}. " +
                "Set -D$CORPUS_PROPERTY=<external material directory>; real acceptance did not execute"
        }
        try {
            val manifest = json(manifestFile)
            check(integer(manifest, "schemaVersion") == 1) { "Unsupported schemaVersion" }
            val corpus = manifest.getJSONObject("corpora").getJSONObject(requirement.corpus)
            val version = text(corpus, "version")
            val english: List<AlignmentChapter>
            val chinese: List<AlignmentChapter>
            var memory: JSONObject? = null
            if (requirement.corpus == "ai-translation") {
                memory = json(verifiedFile(root, corpus.getJSONObject("memory")))
                val en = strings(memory.getJSONArray("enParagraphs"), "enParagraphs")
                val zh = strings(memory.getJSONArray("zhParagraphs"), "zhParagraphs")
                english = listOf(AlignmentChapter("AI sample", en))
                chinese = listOf(AlignmentChapter("AI sample zh", zh))
                validateSide(corpus.getJSONObject("source"), "en", english)
                validateSide(corpus.getJSONObject("target"), "zh", chinese)
                validateBinding(corpus.getJSONObject("truth"), version, english, chinese)
                validatePairs(memory, en, zh)
            } else {
                english = chapters(root, corpus.getJSONObject("source"), "en")
                chinese = chapters(root, corpus.getJSONObject("target"), "zh")
            }
            val dictionary = if (requirement.dictionary) dictionary(root, manifest.getJSONObject("dictionary")) else null
            val golden = if (requirement.golden) {
                val declaration = corpus.getJSONObject("golden")
                validateBinding(declaration, version, english, chinese)
                json(verifiedFile(root, declaration)).also { validateGolden(it, english, chinese) }
            } else null
            report("CORPUS_READY case=$caseId corpus=${requirement.corpus} version=$version " +
                "enSentences=${sentenceCount(english)} zhSentences=${sentenceCount(chinese)} " +
                "sourceTextSha256=${textDigest(english)} targetTextSha256=${textDigest(chinese)} " +
                "dictionaryEntries=${dictionary?.entryCount ?: 0} goldenSamples=${golden?.getJSONArray("samples")?.length() ?: 0}")
            return Loaded(requirement.corpus, version, english, chinese, dictionary, memory, golden)
        } catch (failure: Exception) {
            throw IllegalStateException("$context: invalid/missing input in $manifestFile: ${failure.message}; " +
                "required=${dependencies(requirement)}; real acceptance did not complete", failure)
        }
    }

    private fun dependencies(r: Requirement): String = buildList {
        if (r.corpus == "ai-translation") add("ai_translation_memory + independently reviewed pairs")
        else { add("english_book"); add("chinese_book") }
        if (r.dictionary) add("ecdict_export + export metadata")
        if (r.golden) add("approved_golden bound to both text digests")
    }.joinToString(", ")

    private fun chapters(root: File, side: JSONObject, language: String): List<AlignmentChapter> {
        check(text(side, "language") == language) { "Expected language=$language" }
        text(side, "version")
        val declarations = side.getJSONArray("chapters")
        check(declarations.length() > 0) { "$language chapters must be nonempty and explicitly ordered" }
        val ids = HashSet<String>()
        val paths = HashSet<String>()
        val result = (0 until declarations.length()).map { i ->
            val chapter = declarations.getJSONObject(i)
            check(ids.add(text(chapter, "id"))) { "Duplicate chapter id" }
            val file = verifiedFile(root, chapter)
            check(paths.add(file.canonicalPath)) { "Duplicate chapter path" }
            val paragraphs = when (text(chapter, "format")) {
                "html" -> leafBlocks(file)
                "paragraphs-json" -> strings(json(file).getJSONArray("paragraphs"), "paragraphs", allowEmpty = true)
                else -> error("Unsupported chapter format")
            }
            val title = chapter.get("title")
            check(title is String) { "Chapter title must be a string (the original empty title is allowed)" }
            val loaded = AlignmentChapter(title, paragraphs)
            check(integer(chapter, "paragraphCount") == paragraphs.size) { "paragraphCount mismatch: ${file.name}" }
            check(integer(chapter, "sentenceCount") == sentenceCount(listOf(loaded))) { "sentenceCount mismatch: ${file.name}" }
            loaded
        }
        validateSide(side, language, result)
        return result
    }

    private fun validateSide(side: JSONObject, language: String, chapters: List<AlignmentChapter>) {
        check(text(side, "language") == language) { "Expected language=$language" }
        text(side, "version")
        check(integer(side, "paragraphCount") == chapters.sumOf { it.paragraphs.size }) { "$language paragraphCount mismatch" }
        val count = sentenceCount(chapters)
        check(count > 0 && integer(side, "sentenceCount") == count) { "$language nonempty sentenceCount mismatch (actual=$count)" }
        check(chapters.any { chapter -> chapter.paragraphs.any { paragraph ->
            if (language == "en") paragraph.any { it in 'a'..'z' || it in 'A'..'Z' }
            else paragraph.any { it in '\u4E00'..'\u9FFF' }
        } }) { "$language has no language text" }
        check(digest(side, "textSha256") == textDigest(chapters)) { "$language textSha256 mismatch (chapter/paragraph order or version)" }
    }

    private fun validateBinding(binding: JSONObject, version: String, en: List<AlignmentChapter>, zh: List<AlignmentChapter>) {
        text(binding, "version")
        text(binding, "approvedBy")
        check(text(binding, "method") == "independent-review") { "Truth requires independent-review, not aligner-generated approval" }
        check(text(binding, "corpusVersion") == version) { "Truth corpusVersion mismatch" }
        check(digest(binding, "sourceTextSha256") == textDigest(en)) { "Truth source text digest mismatch" }
        check(digest(binding, "targetTextSha256") == textDigest(zh)) { "Truth target text digest mismatch" }
    }

    private fun validatePairs(memory: JSONObject, en: List<String>, zh: List<String>) {
        val pairs = memory.getJSONArray("pairs")
        check(pairs.length() > 0) { "AI pairs must be nonempty" }
        val sources = HashSet<String>()
        val allEn = matchText(en.joinToString(" "))
        val allZh = matchText(zh.joinToString(" "))
        for (i in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(i)
            val source = matchText(text(pair, "es"))
            val target = matchText(text(pair, "zs"))
            check(source.isNotEmpty() && target.isNotEmpty()) { "Empty normalized pair $i" }
            check(sources.add(source)) { "Ambiguous duplicate AI source pair $i" }
            check(allEn.contains(source) && allZh.contains(target)) { "AI pair $i does not belong to declared texts" }
        }
    }

    private fun validateGolden(golden: JSONObject, en: List<AlignmentChapter>, zh: List<AlignmentChapter>) {
        val samples = golden.getJSONArray("samples")
        check(samples.length() > 0) { "Golden samples must be nonempty" }
        val english = matchText(en.flatMap { it.paragraphs }.joinToString(" "))
        val chinese = matchText(zh.flatMap { it.paragraphs }.joinToString(" "))
        val keys = HashSet<String>()
        var positives = 0
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val source = matchText(text(sample, "en"))
            check(source.isNotEmpty() && keys.add(source) && english.contains(source)) { "Golden source $i absent/duplicate" }
            val approved = sample.getJSONObject("approved")
            val level = text(approved, "level")
            check(level in setOf("MISS", "SENTENCE", "PARAGRAPH")) { "Unknown approved.level=$level" }
            text(sample, "verdict") // Preserve existing verdict vocabulary; 'bad' is the established red line.
            if (level != "MISS") {
                val target = matchText(text(approved, "zh"))
                check(target.isNotEmpty() && chinese.contains(target)) { "Golden target $i absent from this translation" }
                positives++
            }
        }
        check(positives > 0) { "Golden has no positive pair to evaluate" }
    }

    private fun dictionary(root: File, declaration: JSONObject): EcdictProvider {
        val export = verifiedFile(root, declaration)
        val metadata = json(verifiedFile(root, declaration.getJSONObject("metadata")))
        check(integer(metadata, "schemaVersion") == 1 && text(metadata, "exportVersion") == "ecdict-alignment-v1") { "Unsupported dictionary export version" }
        digest(metadata, "sourceSha256")
        check(digest(metadata, "sha256") == sha256(export)) { "Dictionary metadata/export digest mismatch" }
        val provider = EcdictProvider.loadRequired(export)
        check(integer(metadata, "entries") == provider.entryCount && integer(metadata, "forms") == provider.lemmaCount) { "Dictionary metadata counts mismatch" }
        return provider
    }

    internal fun verifyDictionary(root: File): EcdictProvider {
        val manifest = json(File(root, MANIFEST))
        check(integer(manifest, "schemaVersion") == 1) { "Unsupported schemaVersion" }
        return dictionary(root, manifest.getJSONObject("dictionary"))
    }

    internal fun verifiedFile(root: File, declaration: JSONObject): File {
        val relative = text(declaration, "path").replace('\\', '/')
        check(!relative.startsWith('/') && ':' !in relative && relative.none { it.code < 32 } &&
            relative.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe relative path=$relative" }
        // Resolve existing filesystem links before containment checks, including Windows links.
        // File.canonicalFile alone did not resolve the out-of-root link in native Windows CI.
        val realRoot = root.toPath().toRealPath()
        val candidate = realRoot.resolve(relative)
        check(java.nio.file.Files.isRegularFile(candidate)) { "Missing file: $candidate" }
        val realFile = candidate.toRealPath()
        check(realFile.startsWith(realRoot) && realFile != realRoot) { "Path escapes corpus root: $relative" }
        val file = realFile.toFile()
        check(file.isFile) { "Missing file: $file" }
        check(digest(declaration, "sha256") == sha256(file)) { "SHA-256 mismatch: $relative" }
        return file
    }

    private fun leafBlocks(file: File): List<String> {
        val document = Jsoup.parse(utf8(file))
        return document.select(BLOCKS).filter { candidate -> candidate.select(BLOCKS).all { it === candidate } }
            .map { it.text().replace(Regex("\\s+"), " ").trim() }.filter(String::isNotBlank)
    }
    private fun json(file: File) = JSONObject(utf8(file))
    private fun utf8(file: File): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(file.readBytes())).toString()
    private fun strings(array: JSONArray, field: String, allowEmpty: Boolean = false): List<String> {
        check(allowEmpty || array.length() > 0) { "$field must be nonempty" }
        return (0 until array.length()).map { i ->
            val value = array.get(i)
            check(value is String && value.isNotBlank()) { "$field[$i] must be a nonblank string" }
            value
        }
    }
    private fun text(json: JSONObject, key: String): String {
        val value = json.get(key)
        check(value is String && value.isNotBlank()) { "$key must be a nonblank string" }
        return value
    }
    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key)
        check(value is Int && value >= 0) { "$key must be a nonnegative integer" }
        return value
    }
    private fun digest(json: JSONObject, key: String): String = text(json, key).also {
        check(SHA.matches(it)) { "$key must be a lowercase SHA-256" }
    }
    internal fun sentenceCount(chapters: List<AlignmentChapter>): Int = chapters.sumOf { chapter ->
        chapter.paragraphs.sumOf { paragraph -> SentenceSplitter.split(paragraph).count(String::isNotBlank) }
    }
    /** Big-endian int chapter count, then paragraph count and UTF-8 length+bytes per paragraph. */
    internal fun textDigest(chapters: List<AlignmentChapter>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)).use { output ->
            output.writeInt(chapters.size)
            chapters.forEach { chapter ->
                output.writeInt(chapter.paragraphs.size)
                chapter.paragraphs.forEach { paragraph ->
                    val bytes = paragraph.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size); output.write(bytes)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    internal fun matchText(text: String): String = text.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in '\u4E00'..'\u9FFF' }
}

/** Offline CLI on the test runtime classpath. JSON-only summary; no corpus text is exported. */
internal object AlignmentCorpusPreflight {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..4 && args[0] == "--corpus-root") {
            "Usage: --corpus-root <directory> [--case F01|--dictionary-only]"
        }
        val root = File(args[1])
        if (args.size == 3 && args[2] == "--dictionary-only") {
            val provider = AlignmentCorpusInputs.verifyDictionary(root)
            println(JSONObject().put("kind", "dictionary-preflight").put("entries", provider.entryCount)
                .put("forms", provider.lemmaCount).put("apple", provider.chineseCandidates("apple")))
            return
        }
        require(args.size == 2 || args.size == 4 && args[2] == "--case") { "Invalid preflight arguments" }
        val cases = if (args.size == 4) listOf(args[3]) else AlignmentCorpusInputs.requirements.keys.toList()
        val outcomes = cases.map { id ->
            runCatching { AlignmentCorpusInputs.load(root, id, report = {}) }.fold(
                { loaded -> JSONObject().put("case", id).put("status", "inputs-valid")
                    .put("corpus", loaded.corpusId).put("version", loaded.version) },
                { failure -> JSONObject().put("case", id).put("status", "inputs-missing-or-invalid").put("reason", failure.message) }
            )
        }
        println(JSONObject().put("kind", "corpus-input-preflight").put("algorithmExecuted", false).put("cases", outcomes))
        if (outcomes.any { it.getString("status") != "inputs-valid" }) kotlin.system.exitProcess(1)
    }
}
