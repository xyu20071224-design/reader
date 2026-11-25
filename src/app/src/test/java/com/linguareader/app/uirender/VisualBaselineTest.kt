package com.linguareader.app.uirender

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VisualBaselineTest {
    private val ownedFiles = mutableListOf<File>()
    private var previousUpdate: String? = null

    @Before fun ordinaryMode() {
        previousUpdate = System.getProperty("updateVisualBaseline")
        System.clearProperty("updateVisualBaseline")
    }

    @After fun restore() {
        ownedFiles.forEach { it.delete() }
        previousUpdate?.let { System.setProperty("updateVisualBaseline", it) }
            ?: System.clearProperty("updateVisualBaseline")
    }

    private fun baselineFile(): File = File("src/test/resources/visual-baseline",
        "visual-contract-${UUID.randomUUID()}.png").also { ownedFiles += it }

    private fun bitmap(width: Int = 10, height: Int = 20, color: Int = Color.rgb(100, 100, 100)) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun write(file: File, bitmap: Bitmap) {
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun missingBaselineCannotPassOrdinaryAcceptance() {
        val file = baselineFile()
        val result = VisualBaseline.compare(file.nameWithoutExtension, bitmap())
        assertFalse("Missing baseline must fail acceptance", result.passed)
    }

    @Test fun ordinaryComparisonNeverCreatesMissingReference() {
        val file = baselineFile()
        VisualBaseline.compare(file.nameWithoutExtension, bitmap())
        assertFalse("Read-only comparison must not generate a reference", file.exists())
    }

    @Test fun corruptBaselineCannotPassOrdinaryAcceptance() {
        val file = baselineFile().apply { writeText("not a PNG") }
        val original = file.readBytes()
        val result = VisualBaseline.compare(file.nameWithoutExtension, bitmap())
        assertFalse("Undecodable baseline must fail acceptance", result.passed)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun comparisonIsReadOnlyEvenWhenGenerationWasRequested() {
        val file = baselineFile()
        write(file, bitmap())
        val original = file.readBytes()
        System.setProperty("updateVisualBaseline", "true")
        val result = VisualBaseline.compare(file.nameWithoutExtension, bitmap(color = Color.BLUE))
        assertFalse(result.passed)
        assertArrayEquals("Generation must be a separate action", original, file.readBytes())
    }

    @Test fun dimensionsMismatchIsRejected() {
        val file = baselineFile()
        write(file, bitmap())
        assertThrows(IllegalArgumentException::class.java) {
            VisualBaseline.compare(file.nameWithoutExtension, bitmap(20, 10))
        }
    }

    @Test fun channelDeltaTwelveDoesNotCountAsDifferent() {
        val file = baselineFile()
        write(file, bitmap())
        val result = VisualBaseline.compare(file.nameWithoutExtension, bitmap(color = Color.rgb(112, 100, 100)))
        assertEquals(0, result.diffPixels)
        assertEquals(12, result.worstDelta)
        assertTrue(result.passed)
    }

    @Test fun halfPercentAllowsOneLargeDeltaPixelButRejectsTwo() {
        val file = baselineFile()
        write(file, bitmap())
        val actual = bitmap().apply { setPixel(0, 0, Color.WHITE) }
        val one = VisualBaseline.compare(file.nameWithoutExtension, actual)
        assertEquals(1, one.diffPixels)
        assertEquals(200, one.totalPixels)
        assertEquals(155, one.worstDelta)
        assertTrue("0.5% is inclusive; worst delta is diagnostic", one.passed)
        actual.setPixel(1, 0, Color.rgb(113, 100, 100))
        val two = VisualBaseline.compare(file.nameWithoutExtension, actual)
        assertEquals(2, two.diffPixels)
        assertFalse(two.passed)
    }

    @Test fun identicalReferencePassesWithoutChangingBytes() {
        val file = baselineFile()
        val actual = bitmap()
        write(file, actual)
        val original = file.readBytes()
        val result = VisualBaseline.compare(file.nameWithoutExtension, actual)
        assertTrue(result.passed)
        assertEquals(0, result.diffPixels)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun explicitGenerationIsRejectedInOrdinaryMode() {
        val file = baselineFile()
        assertThrows(IllegalStateException::class.java) {
            VisualBaseline.generate(file.nameWithoutExtension, bitmap())
        }
        assertFalse(file.exists())
    }

    @Test fun explicitGenerationRequiresANewOrdinaryComparison() {
        val file = baselineFile()
        val actual = bitmap()
        System.setProperty("updateVisualBaseline", "true")
        assertEquals(file.absoluteFile, VisualBaseline.generate(file.nameWithoutExtension, actual).absoluteFile)
        val generated = file.readBytes()
        System.clearProperty("updateVisualBaseline")
        val result = VisualBaseline.compare(file.nameWithoutExtension, actual)
        assertTrue(result.passed)
        assertArrayEquals(generated, file.readBytes())
    }

    @Test fun onlyExplicitGenerationCanReplaceCorruptReference() {
        val file = baselineFile().apply { writeText("broken") }
        assertFalse(VisualBaseline.compare(file.nameWithoutExtension, bitmap()).passed)
        assertEquals("broken", file.readText())
        System.setProperty("updateVisualBaseline", "true")
        VisualBaseline.generate(file.nameWithoutExtension, bitmap())
        System.clearProperty("updateVisualBaseline")
        assertTrue(VisualBaseline.compare(file.nameWithoutExtension, bitmap()).passed)
    }
}
