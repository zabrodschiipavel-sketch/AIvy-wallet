package ivy.automate.compose.stability

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ivy.compose.reports.composeReportFingerprint
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComposeReportInputsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun editSourceChangesFingerprint() {
        val root = temporary.newFolder("edit")
        val source = File(root, "src/main/Screen.kt")
        source.parentFile.mkdirs()
        source.writeText("v1")
        val before = composeReportFingerprint(root)
        source.writeText("v2")
        composeReportFingerprint(root) shouldNotBe before
    }

    @Test
    fun addSecondSourceChangesFingerprint() {
        val root = temporary.newFolder("add")
        val first = File(root, "src/main/Screen.kt")
        first.parentFile.mkdirs()
        first.writeText("v1")
        val before = composeReportFingerprint(root)
        File(root, "src/main/Other.kt").writeText("v2")
        composeReportFingerprint(root) shouldNotBe before
    }

    @Test
    fun generatedOutputsDoNotChangeFingerprint() {
        val root = temporary.newFolder("ignored")
        val source = File(root, "src/main/Screen.kt")
        source.parentFile.mkdirs()
        source.writeText("v1")
        val before = composeReportFingerprint(root)
        File(root, "build/generated/Screen.kt").apply {
            parentFile.mkdirs()
            writeText("gen")
        }
        File(root, "outputs/copy/Screen.kt").apply {
            parentFile.mkdirs()
            writeText("copy")
        }
        composeReportFingerprint(root) shouldBe before
    }
}
