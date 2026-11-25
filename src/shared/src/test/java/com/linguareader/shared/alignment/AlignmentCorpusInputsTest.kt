package com.linguareader.shared.alignment

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small original texts validate the loader only; these never replace the 17 external-book cases. */
class AlignmentCorpusInputsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun repositoryDiscoveryUsesCodeMarkersFromModuleAndNestedWorkingDirectories() {
        val repo = temporary.newFolder("source-archive")
        File(repo, "src/settings.gradle.kts").apply { parentFile.mkdirs(); writeText("// fixture") }
        File(repo, "src/shared/src/main/java/com/linguareader/shared/alignment/AlignmentModels.kt")
            .apply { parentFile.mkdirs(); writeText("// fixture") }
        val module = File(repo, "src/shared/nested").apply { mkdirs() }
        assertEquals(repo.canonicalFile, AlignmentCorpusInputs.repoRoot(module, null))
        assertEquals(repo.canonicalFile, AlignmentCorpusInputs.repoRoot(temporary.root, repo.path))
        assertFalse(File(repo, ".git").exists())
        assertFalse(File(repo, "artifacts/lotr-book").exists())
        assertFailsWith<IllegalStateException> { AlignmentCorpusInputs.repoRoot(module, temporary.root.path) }
        assertFailsWith<IllegalStateException> { AlignmentCorpusInputs.repoRoot(temporary.root, null) }
    }

    @Test fun missingCorpusIsExplicitFailureAndAiHasNoLotrDependency() {
        val failure = assertFailsWith<IllegalStateException> { AlignmentCorpusInputs.load(temporary.root, "F02") }
        assertTrue(failure.message.orEmpty().contains("F02 corpus=ai-translation"))
        assertTrue(failure.message.orEmpty().contains("ai_translation_memory"))
        assertTrue(failure.message.orEmpty().contains("ecdict_export"))
        assertFalse(failure.message.orEmpty().contains("english_book"))
    }

    @Test fun explicitOrderAndWindowsRelativeSeparatorsWorkWithoutOptionalDictionaryOrGolden() {
        val f = fixture()
        val source = f.manifest.getJSONObject("corpora").getJSONObject("lotr").getJSONObject("source")
        source.getJSONArray("chapters").getJSONObject(0).put("path", "texts\\en-second.json")
        f.save()
        val loaded = AlignmentCorpusInputs.load(f.root, "F12")
        assertEquals(listOf("Second in filename, first in reading order", "First in filename, second in reading order"), loaded.english.map { it.title })
        assertEquals(null, loaded.dictionary)
        assertEquals(null, loaded.golden)
    }

    @Test fun emptySideOrChapterWithIncorrectCountsCannotPass() {
        val f = fixture()
        f.lotr.getJSONObject("source").put("chapters", JSONArray())
        f.save()
        assertInvalid(f, "F12", "chapters")
        val g = fixture()
        val file = File(g.root, "texts/en-second.json").apply { writeText("{\"paragraphs\":[]}") }
        g.lotr.getJSONObject("source").getJSONArray("chapters").getJSONObject(0).put("sha256", AlignmentCorpusInputs.sha256(file))
        g.save()
        assertInvalid(g, "F12", "paragraphCount")
    }

    @Test fun fileDigestCountsAndTextOrderAreAllVerified() {
        val corrupt = fixture()
        File(corrupt.root, "texts/en-second.json").appendText(" ")
        assertInvalid(corrupt, "F12", "SHA-256 mismatch")
        val counts = fixture()
        counts.lotr.getJSONObject("source").put("sentenceCount", 999)
        counts.save(); assertInvalid(counts, "F12", "sentenceCount")
        val order = fixture()
        val source = order.lotr.getJSONObject("source")
        val chapters = source.getJSONArray("chapters")
        source.put("chapters", JSONArray(listOf(chapters.getJSONObject(1), chapters.getJSONObject(0))))
        order.save(); assertInvalid(order, "F12", "textSha256")
    }

    @Test fun traversalAbsoluteDriveUncAndDuplicatePathsAreRejected() {
        for (path in listOf("../escape.json", "/absolute.json", "C:\\books\\chapter.json", "\\\\server\\share", "texts/./en-second.json")) {
            val f = fixture()
            f.lotr.getJSONObject("source").getJSONArray("chapters").getJSONObject(0).put("path", path)
            f.save(); assertInvalid(f, "F12", "Unsafe relative path")
        }
        val f = fixture()
        val chapters = f.lotr.getJSONObject("source").getJSONArray("chapters")
        val repeated = JSONObject(chapters.getJSONObject(0).toString()).put("id", "different-id")
        chapters.put(1, repeated)
        f.save(); assertInvalid(f, "F12", "Duplicate chapter path")
    }

    @Test fun symbolicLinkOutsideCorpusIsRejected() {
        val f = fixture()
        val outside = temporary.newFile("outside.json").apply { writeText("{}") }
        val link = File(f.root, "texts/link.json")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        f.lotr.getJSONObject("source").getJSONArray("chapters").getJSONObject(0)
            .put("path", "texts/link.json").put("sha256", AlignmentCorpusInputs.sha256(outside))
        f.save(); assertInvalid(f, "F12", "escapes corpus root")
    }

    @Test fun missingSingleDependencyAndFieldNameAreReported() {
        val f = fixture()
        f.manifest.remove("dictionary")
        f.save(); assertInvalid(f, "F11", "dictionary")
        val g = fixture()
        File(g.root, "golden.json").delete()
        assertInvalid(g, "F11", "golden.json")
        val h = fixture()
        h.lotr.getJSONObject("source").getJSONArray("chapters").getJSONObject(0).remove("sha256")
        h.save(); assertInvalid(h, "F12", "sha256")
    }

    @Test fun wrongCorpusVersionAndWrongTranslationGoldenAreRejected() {
        val version = fixture()
        version.lotr.getJSONObject("golden").put("corpusVersion", "another-edition")
        version.save(); assertInvalid(version, "F11", "corpusVersion")
        val digest = fixture()
        digest.lotr.getJSONObject("golden").put("targetTextSha256", "0".repeat(64))
        digest.save(); assertInvalid(digest, "F11", "target text digest")
        val generated = fixture()
        generated.lotr.getJSONObject("golden").put("method", "aligner-output")
        generated.save(); assertInvalid(generated, "F11", "independent-review")
    }

    @Test fun goldenMustContainReviewedNonemptyPairsBelongingToTheBoundTexts() {
        for (samples in listOf(JSONArray(), JSONArray().put(JSONObject().put("en", "Someone else's source.")
            .put("verdict", "bad").put("approved", JSONObject().put("level", "MISS"))))) {
            val f = fixture()
            val file = File(f.root, "golden.json").apply { writeText(JSONObject().put("samples", samples).toString()) }
            f.lotr.getJSONObject("golden").put("sha256", AlignmentCorpusInputs.sha256(file))
            f.save(); assertInvalid(f, "F11", if (samples.length() == 0) "nonempty" else "Golden source")
        }
        val f = fixture()
        val samples = JSONArray().put(JSONObject().put("en", f.en[0].paragraphs[0]).put("verdict", "good")
            .put("approved", JSONObject().put("level", "SENTENCE").put("zh", "这是另一译本的句子。")))
        val file = File(f.root, "golden.json").apply { writeText(JSONObject().put("samples", samples).toString()) }
        f.lotr.getJSONObject("golden").put("sha256", AlignmentCorpusInputs.sha256(file))
        f.save(); assertInvalid(f, "F11", "Golden target")
    }

    @Test fun aiOnlyCorpusLoadsAndRequiresNoRealBookOrSeparateGolden() {
        val f = fixture()
        f.manifest.getJSONObject("corpora").remove("lotr")
        f.save()
        val loaded = AlignmentCorpusInputs.load(f.root, "F02")
        assertEquals(2, loaded.requireMemory().getJSONArray("pairs").length())
        assertEquals(listOf("苹果"), loaded.requireDictionary().chineseCandidates("apples"))
        assertEquals(null, loaded.golden)
    }

    @Test fun reviewedGoldenLoadsOnlyAgainstTheBoundTextEdition() {
        val f = fixture()
        val loaded = AlignmentCorpusInputs.load(f.root, "F11")
        assertEquals("loader-only-v1", loaded.version)
        assertEquals(1, loaded.requireGolden().getJSONArray("samples").length())
        assertEquals(listOf("苹果"), loaded.requireDictionary().chineseCandidates("apples"))
    }

    @Test fun htmlUsesLeafBlocksWithoutDuplicatingParentContainers() {
        val f = fixture()
        val chapter = f.lotr.getJSONObject("source").getJSONArray("chapters").getJSONObject(0)
        val html = File(f.root, "texts/leaf.xhtml").apply {
            writeText("<html><body><section><div><p>${f.en[0].paragraphs[0]}</p></div></section></body></html>")
        }
        chapter.put("path", "texts/leaf.xhtml").put("format", "html").put("sha256", AlignmentCorpusInputs.sha256(html))
        f.save()
        assertEquals(f.en[0].paragraphs, AlignmentCorpusInputs.load(f.root, "F12").english[0].paragraphs)
    }

    @Test fun explicitlyEmptyStructuralChapterIsPreservedRatherThanSilentlyChangingBookOrder() {
        val f = fixture()
        val source = f.lotr.getJSONObject("source")
        val cover = File(f.root, "texts/cover.xhtml").apply { writeText("<html><body><div><img src='cover.jpg'/></div></body></html>") }
        val declaration = JSONObject().put("id", "cover").put("title", "Image cover").put("path", "texts/cover.xhtml")
            .put("sha256", AlignmentCorpusInputs.sha256(cover)).put("format", "html").put("paragraphCount", 0).put("sentenceCount", 0)
        val chapters = source.getJSONArray("chapters")
        source.put("chapters", JSONArray().put(declaration).put(chapters.getJSONObject(0)).put(chapters.getJSONObject(1)))
        source.put("textSha256", AlignmentCorpusInputs.textDigest(listOf(AlignmentChapter("Image cover")) + f.en))
        f.save()
        val loaded = AlignmentCorpusInputs.load(f.root, "F12")
        assertEquals(3, loaded.english.size)
        assertTrue(loaded.english[0].paragraphs.isEmpty())
        assertEquals(f.en, loaded.english.drop(1))
    }

    @Test fun aiEmptyDuplicateAndForeignPairsAreRejected() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("pairs", JSONArray()) },
            { it.getJSONArray("pairs").put(JSONObject(it.getJSONArray("pairs").getJSONObject(0).toString())) },
            { it.getJSONArray("pairs").getJSONObject(0).put("zs", "别人的翻译。") },
            { it.put("enParagraphs", JSONArray()) }
        )) {
            val f = fixture()
            val file = File(f.root, "memory.json")
            val memory = JSONObject(file.readText()).also(change)
            file.writeText(memory.toString())
            f.ai.getJSONObject("memory").put("sha256", AlignmentCorpusInputs.sha256(file))
            f.save(); assertFailsWith<IllegalStateException> { AlignmentCorpusInputs.load(f.root, "F02") }
        }
    }

    @Test fun dictionaryMetadataAndTsvAreCheckedIncludingLemmaReferences() {
        val f = fixture()
        val metadata = File(f.root, "dict/metadata.json")
        metadata.writeText(JSONObject(metadata.readText()).put("entries", 99).toString())
        f.manifest.getJSONObject("dictionary").getJSONObject("metadata").put("sha256", AlignmentCorpusInputs.sha256(metadata))
        f.save(); assertInvalid(f, "F02", "counts mismatch")
        for (contents in listOf("", "broken", "apple\t苹果\napple\t果实\n", "apple\t苹果\n>apples\tabsent\n", "apple\t苹果|\n")) {
            val file = temporary.newFile().apply { writeText(contents) }
            assertFailsWith<IllegalStateException> { EcdictProvider.loadRequired(file) }
        }
    }

    @Test fun malformedUtf8DuplicateKeysAndUnsupportedVersionsFailClosed() {
        val malformed = fixture()
        File(malformed.root, AlignmentCorpusInputs.MANIFEST).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertInvalid(malformed, "F12", "input")
        val duplicate = fixture()
        File(duplicate.root, AlignmentCorpusInputs.MANIFEST).writeText("{\"schemaVersion\":1,\"schemaVersion\":1}")
        assertInvalid(duplicate, "F12", "Duplicate key")
        val version = fixture()
        version.manifest.put("schemaVersion", 2); version.save()
        assertInvalid(version, "F12", "schemaVersion")
    }

    private data class Fixture(val root: File, val manifest: JSONObject, val en: List<AlignmentChapter>) {
        val lotr get() = manifest.getJSONObject("corpora").getJSONObject("lotr")
        val ai get() = manifest.getJSONObject("corpora").getJSONObject("ai-translation")
        fun save() = File(root, AlignmentCorpusInputs.MANIFEST).writeText(manifest.toString(2))
    }

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val en = listOf(AlignmentChapter("Second in filename, first in reading order", listOf("An apple rests beside the basket.")),
            AlignmentChapter("First in filename, second in reading order", listOf("The river flows toward the village.")))
        val zh = listOf(AlignmentChapter("中文一", listOf("一个苹果放在篮子旁边。")),
            AlignmentChapter("中文二", listOf("河流流向村庄。")))
        fun write(path: String, content: String): File = File(root, path).apply { parentFile.mkdirs(); writeText(content) }
        fun ref(file: File) = JSONObject().put("path", file.relativeTo(root).invariantSeparatorsPath).put("sha256", AlignmentCorpusInputs.sha256(file))
        fun side(language: String, chapters: List<AlignmentChapter>): JSONObject {
            val rows = JSONArray()
            chapters.forEachIndexed { i, chapter ->
                val file = write("texts/$language-${if (i == 0) "second" else "first"}.json", JSONObject().put("paragraphs", chapter.paragraphs).toString())
                rows.put(ref(file).put("id", "$language-$i").put("title", chapter.title).put("format", "paragraphs-json")
                    .put("paragraphCount", chapter.paragraphs.size).put("sentenceCount", AlignmentCorpusInputs.sentenceCount(listOf(chapter))))
            }
            return counts(language, chapters).put("chapters", rows)
        }
        fun binding(en: List<AlignmentChapter>, zh: List<AlignmentChapter>) = JSONObject().put("version", "review-v1")
            .put("approvedBy", "original-loader-fixture-author").put("method", "independent-review").put("corpusVersion", "loader-only-v1")
            .put("sourceTextSha256", AlignmentCorpusInputs.textDigest(en)).put("targetTextSha256", AlignmentCorpusInputs.textDigest(zh))
        val golden = write("golden.json", JSONObject().put("samples", JSONArray().put(JSONObject().put("en", en[0].paragraphs[0])
            .put("verdict", "good").put("approved", JSONObject().put("level", "SENTENCE").put("zh", zh[0].paragraphs[0])))).toString())
        val lotr = JSONObject().put("version", "loader-only-v1").put("source", side("en", en)).put("target", side("zh", zh))
            .put("golden", binding(en, zh).put("path", "golden.json").put("sha256", AlignmentCorpusInputs.sha256(golden)))
        val aiEn = listOf(AlignmentChapter("AI sample", en.flatMap { it.paragraphs }))
        val aiZh = listOf(AlignmentChapter("AI sample zh", zh.flatMap { it.paragraphs }))
        val pairs = JSONArray()
        en.indices.forEach { i -> pairs.put(JSONObject().put("es", en[i].paragraphs[0]).put("zs", zh[i].paragraphs[0])) }
        val memory = write("memory.json", JSONObject().put("enParagraphs", aiEn[0].paragraphs).put("zhParagraphs", aiZh[0].paragraphs).put("pairs", pairs).toString())
        val ai = JSONObject().put("version", "loader-only-v1").put("memory", ref(memory)).put("source", counts("en", aiEn))
            .put("target", counts("zh", aiZh)).put("truth", binding(aiEn, aiZh))
        val tsv = write("dict/ecdict.tsv", "apple\t苹果\nriver\t河流\n>apples\tapple\n")
        val metadata = write("dict/metadata.json", JSONObject().put("schemaVersion", 1).put("exportVersion", "ecdict-alignment-v1")
            .put("sourceSha256", "a".repeat(64)).put("sha256", AlignmentCorpusInputs.sha256(tsv)).put("entries", 2).put("forms", 1).toString())
        val manifest = JSONObject().put("schemaVersion", 1).put("corpora", JSONObject().put("lotr", lotr).put("ai-translation", ai))
            .put("dictionary", ref(tsv).put("metadata", ref(metadata)))
        return Fixture(root, manifest, en).also(Fixture::save)
    }

    private fun counts(language: String, chapters: List<AlignmentChapter>) = JSONObject().put("language", language).put("version", "original-text-v1")
        .put("paragraphCount", chapters.sumOf { it.paragraphs.size }).put("sentenceCount", AlignmentCorpusInputs.sentenceCount(chapters))
        .put("textSha256", AlignmentCorpusInputs.textDigest(chapters))
    private fun assertInvalid(fixture: Fixture, case: String, message: String) {
        val failure = assertFailsWith<IllegalStateException> { AlignmentCorpusInputs.load(fixture.root, case) }
        assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }
}
