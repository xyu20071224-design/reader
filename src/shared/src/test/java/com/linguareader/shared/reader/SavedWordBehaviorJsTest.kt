package com.linguareader.shared.reader

import com.linguareader.shared.data.ReaderPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses ReaderScripts.bootstrap generated now, not a historical JS snapshot or a copied matcher. */
class SavedWordBehaviorJsTest {
    private fun row(id: String, vararg parts: Any) = JSONObject().put("id", id).put("parts", JSONArray(parts.toList()))
    private fun inline(text: String) = JSONObject().put("tag", "em").put("text", text)
    private fun action(words: List<String>, due: List<String> = emptyList()) =
        JSONObject().put("script", ReaderScripts.savedWordsScript(words, due))

    private fun run(rows: List<JSONObject>, actions: List<JSONObject>): JSONArray {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("Node is required for the generated-script text DOM matrix", node)
        val dir = File.createTempFile("saved-word-matrix-", "").apply { delete(); mkdirs() }
        try {
            val bootstrap = File(dir, "bootstrap.js").apply { writeText(ReaderScripts.bootstrap(0, ReaderPreferences())) }
            val fixture = File(dir, "fixture.json").apply {
                writeText(JSONObject().put("rows", JSONArray(rows)).put("actions", JSONArray(actions)).toString())
            }
            val output = File(dir, "output.txt")
            val runner = File("src/test/resources/reader/run-saved-word-matrix.js")
            assertTrue(runner.isFile, "Missing executable DOM fixture")
            val process = ProcessBuilder("node", runner.absolutePath, bootstrap.absolutePath, fixture.absolutePath)
                .redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "Generated-script matrix timed out")
            assertEquals(0, process.exitValue(), output.readText())
            return JSONArray(output.readText().trim())
        } finally { dir.deleteRecursively() }
    }
    private fun marks(result: JSONArray, step: Int, id: String, type: String = "saved"): List<String> =
        result.getJSONObject(step).getJSONObject(id).getJSONArray(type).let { a -> (0 until a.length()).map(a::getString) }

    @Test fun b01IndependentWordsMatchButPrefixesDoNot() {
        val r = run(listOf(row("positive", "app APP app,\napp."), row("negative", "apple application happy")), listOf(action(listOf("app"))))
        assertEquals(listOf("app", "APP", "app", "app"), marks(r, 0, "positive"))
        assertEquals(emptyList(), marks(r, 0, "negative"))
    }

    @Test fun b01InlineMarkupCannotTurnAppleIntoAnIndependentApp() {
        val r = run(listOf(row("negative", "app", inline("le"))), listOf(action(listOf("app"))))
        assertEquals(emptyList(), marks(r, 0, "negative"))
    }

    @Test fun t02RegularInflectionsKeepTheWholeOriginalSpellingInBothStyles() {
        val words = listOf("run", "stop", "study", "make")
        val r = run(listOf(row("positive", "run RUNNING stop stopped study studies studied make making"),
            row("negative", "runway unstoppable understudy remake")), listOf(action(words), action(words, words)))
        val expected = "run RUNNING stop stopped study studies studied make making".split(' ')
        assertEquals(expected, marks(r, 0, "positive"))
        assertEquals(expected, marks(r, 1, "positive", "due"))
        assertEquals(emptyList(), marks(r, 0, "negative"))
        assertEquals(emptyList(), marks(r, 1, "negative", "due"))
    }

    @Test fun t07ChangingFromWordToPhraseAndBackDoesNotLoseThePhrase() {
        val r = run(listOf(row("phrase", "give up")), listOf(action(listOf("give")), action(listOf("give up")),
            action(listOf("give", "give up")), action(listOf("give")), action(emptyList())))
        assertEquals(listOf("give"), marks(r, 0, "phrase"))
        assertEquals(listOf("give up"), marks(r, 1, "phrase"))
        assertEquals(listOf("give up"), marks(r, 2, "phrase"))
        assertEquals(listOf("give"), marks(r, 3, "phrase"))
        assertEquals(emptyList(), marks(r, 4, "phrase"))
    }

    @Test fun t07WhitespaceEquivalentPhrasesUseTheDueStyle() {
        val r = run(listOf(row("phrase", "give\n  up GIVE\tUP")), listOf(action(listOf("give up"), listOf("give up"))))
        assertEquals(listOf("give\n  up", "GIVE\tUP"), marks(r, 0, "phrase", "due"))
        assertEquals(emptyList(), marks(r, 0, "phrase"))
    }

    @Test fun t07InlinePhraseKeepsItsSourceCharactersAndFormatting() {
        val r = run(listOf(row("phrase", "give ", inline("up"))), listOf(action(listOf("give up"))))
        assertEquals("give up", marks(r, 0, "phrase").joinToString(""))
    }

    @Test fun t08SeenIrregularSurfaceFormsAreExactAndUnseenFormsAreNotInvented() {
        val r = run(listOf(row("forms", "go went gone WENT wagon")), listOf(action(listOf("go", "went"))))
        assertEquals(listOf("go", "went", "WENT"), marks(r, 0, "forms"))
    }

    @Test fun t11RefreshDueFutureAndOffNeverChangesTextOrNestsMarks() {
        val actions = (1..12).map { action(listOf("study"), if (it % 2 == 0) listOf("study") else emptyList()) } + action(emptyList())
        val r = run(listOf(row("word", "studied study")), actions)
        for (index in 0 until 12) assertEquals(listOf("studied", "study"), marks(r, index, "word", if (index % 2 == 0) "saved" else "due"))
        assertEquals(emptyList(), marks(r, 12, "word"))
        assertEquals(emptyList(), marks(r, 12, "word", "due"))
    }

    @Test fun t11DueFormsRetainPriorityWhenOrdinaryInflectionsFillTheBudget() {
        val r = run(listOf(row("due", "study studied")), listOf(action((1..600).map { "ordinaryword$it" }, listOf("study"))))
        assertEquals(listOf("study", "studied"), marks(r, 0, "due", "due"))
    }
}
