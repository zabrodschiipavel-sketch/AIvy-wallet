package ivy.automate.compose.stability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import ivy.compose.reports.composeFileSha256
import ivy.compose.reports.composeReportFingerprint
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class ReportProvenanceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `demo app and release library require their own production reports`() {
        listOf("app", "ui").forEach { name ->
            val root = temporary.newFolder()
            val folder = report(root, name)
            verifyReportProvenance(folder, composeReportFingerprint(root)) shouldBe "session"
            productionReport(folder).name shouldBe "${name}_${if (name == "app") "demo" else "release"}-composables.csv"
        }
    }

    @Test
    fun `fresh complete module may have no composables`() {
        val root = temporary.newFolder()
        val folder = report(root)
        productionReport(folder).writeText("$Header\n")
        textReport(folder).writeText("")
        stamp(folder, root)
        verifyReportProvenance(folder, composeReportFingerprint(root)) shouldBe "session"
    }

    @Test
    fun `missing marker or report pair fails`() {
        listOf("full-compilation.properties", "ui_release-composables.csv", "ui_release-composables.txt").forEach {
            val root = temporary.newFolder()
            val folder = report(root)
            File(folder, it).delete()
            shouldThrow<IllegalStateException> { verifyReportProvenance(folder, composeReportFingerprint(root)) }
        }
    }

    @Test
    fun `invalid full compilation metadata fails`() {
        listOf("format" to "2", "variant" to "debug", "incremental" to "true", "session" to " ",
            "sourcesSha256" to "old", "csvSha256" to "", "textSha256" to "").forEach { (key, value) ->
            val root = temporary.newFolder()
            val folder = report(root)
            val marker = File(folder, "full-compilation.properties")
            val properties = Properties().apply { marker.inputStream().use { load(it) } }
            properties.setProperty(key, value)
            marker.outputStream().use { properties.store(it, null) }
            shouldThrow<IllegalStateException> { verifyReportProvenance(folder, composeReportFingerprint(root)) }
        }
    }

    @Test
    fun `truncated CSV fails even when another module has valid rows`() {
        val root = temporary.newFolder()
        val first = report(root, "first")
        report(root, "second")
        stamp(first, root)
        productionReport(first).writeText("$Header\n")
        shouldThrow<IllegalStateException> { readFullComposeReports(root) }
    }

    @Test
    fun `changed companion text fails`() {
        val root = temporary.newFolder()
        val folder = report(root)
        textReport(folder).writeText("partial fun Screen()")
        shouldThrow<IllegalStateException> { verifyReportProvenance(folder, composeReportFingerprint(root)) }
    }

    @Test
    fun `debug reports cannot replace missing production reports`() {
        val root = temporary.newFolder()
        val folder = report(root)
        productionReport(folder).renameTo(File(folder, "ui_debug-composables.csv"))
        textReport(folder).renameTo(File(folder, "ui_debug-composables.txt"))
        shouldThrow<IllegalStateException> { verifyReportProvenance(folder, composeReportFingerprint(root)) }
    }

    @Test
    fun `debug and test outputs cannot affect production analysis`() {
        val root = temporary.newFolder()
        val folder = report(root)
        File(folder, "ui_debug-composables.csv").writeText("invalid debug report")
        File(folder, "ui_releaseUnitTest-composables.csv").writeText("invalid test report")
        readComposeReports(listOf(folder), productionOnly = true).shouldBeEmpty()
    }

    @Test
    fun `reports from different full compilations fail`() {
        val root = temporary.newFolder()
        val first = report(root, "first")
        val second = report(root, "second")
        stamp(first, root)
        stamp(second, root, "another-session")
        val error = shouldThrow<IllegalStateException> { readFullComposeReports(root) }
        error.message shouldBe "Compose reports belong to different full compilations."
    }

    @Test
    fun `new Compose module requires its own completion marker`() {
        val root = temporary.newFolder()
        val first = report(root, "first")
        val second = report(root, "second")
        File(second, "full-compilation.properties").delete()
        stamp(first, root)
        shouldThrow<IllegalStateException> { readFullComposeReports(root) }
    }

    private fun report(root: File, name: String = "ui"): File {
        val module = File(root, name).apply { mkdirs() }
        File(module, "build.gradle.kts").writeText("plugins { id(\"ivy.compose\") }")
        return File(module, "build/compose_full").apply {
            mkdirs()
            productionReport(this).writeText("$Header\nexample,Screen,1,1,1,\n")
            textReport(this).writeText("restartable skippable fun Screen()")
            stamp(this, root)
        }
    }

    private fun textReport(folder: File): File = File(folder, productionReport(folder).name.replace(".csv", ".txt"))

    private fun stamp(folder: File, root: File, session: String = "session") {
        val properties = Properties().apply {
            setProperty("format", "1")
            setProperty("variant", if (folder.parentFile.parentFile.name == "app") "demo" else "release")
            setProperty("incremental", "false")
            setProperty("session", session)
            setProperty("sourcesSha256", composeReportFingerprint(root))
            setProperty("csvSha256", composeFileSha256(productionReport(folder)))
            setProperty("textSha256", composeFileSha256(textReport(folder)))
        }
        File(folder, "full-compilation.properties").outputStream().use { properties.store(it, null) }
    }

    private companion object {
        const val Header = "package,name,composable,skippable,restartable,"
    }
}
