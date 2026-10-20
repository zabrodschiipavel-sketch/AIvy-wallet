package ivy.automate.compose.stability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ComposeReportsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `missing report folders fail`() {
        shouldThrow<IllegalStateException> { readComposeReports(emptyList()) }
    }

    @Test
    fun `empty report folder fails`() {
        shouldThrow<IllegalStateException> { readComposeReports(listOf(temporary.newFolder())) }
    }

    @Test
    fun `missing companion fails`() {
        val folder = report("example,Screen,1,1,1,")
        File(folder, "sample-composables.txt").delete()
        shouldThrow<IllegalStateException> { readComposeReports(listOf(folder)) }
    }

    @Test
    fun `malformed and empty CSV fail`() {
        listOf("", "bad,header", Header, "$Header\nshort,row", "$Header\nexample,Screen,1,2,1,").forEach { csv ->
            val folder = report("example,Screen,1,1,1,")
            File(folder, "sample-composables.csv").writeText(csv)
            shouldThrow<IllegalStateException> { readComposeReports(listOf(folder)) }
        }
    }

    @Test
    fun `stable report passes`() {
        readComposeReports(listOf(report("example,Screen,1,1,1,"))).shouldBeEmpty()
    }

    @Test
    fun `all variants are checked and unstable composable is retained`() {
        val folder = report("example,Screen,1,1,1,")
        File(folder, "release-composables.csv").writeText("$Header\nexample,Other,1,0,1,\n")
        File(folder, "release-composables.txt").writeText("restartable fun Other()")
        readComposeReports(listOf(folder)).single().name shouldBe "Other"
    }

    @Test
    fun `discovery includes explicit and convention Compose modules only`() {
        val root = temporary.newFolder()
        module(root, "app", "org.jetbrains.kotlin.plugin.compose")
        module(root, "feature", "id(\"ivy.feature\")")
        module(root, "ui", "id(\"ivy.compose\")")
        module(root, "explicit", "id(\"org.jetbrains.kotlin.plugin.compose\")")
        module(root, "plain", "id(\"org.jetbrains.kotlin.jvm\")")
        module(root, "build/generated", "id(\"ivy.feature\")")
        module(root, ".git/fixture", "org.jetbrains.kotlin.plugin.compose")

        findComposeReportFolders(root).map { it.relativeTo(root).invariantSeparatorsPath }.toList() shouldBe
            listOf("app", "explicit", "feature", "ui").map { "$it/build/compose_compiler" }
    }

    @Test
    fun `missing app reports fail even when a feature has valid reports`() {
        val root = temporary.newFolder()
        module(root, "app", "org.jetbrains.kotlin.plugin.compose")
        val feature = module(root, "feature", "id(\"ivy.feature\")")
        val folder = File(feature, "build/compose_compiler").apply { mkdirs() }
        File(folder, "sample-composables.csv").writeText("$Header\nexample,Screen,1,1,1,\n")
        File(folder, "sample-composables.txt").writeText("restartable skippable fun Screen()")
        readComposeReports(listOf(folder)).shouldBeEmpty()

        val error = shouldThrow<IllegalStateException> {
            readComposeReports(findComposeReportFolders(root).toList())
        }
        error.message shouldBe "No composables CSV reports in ${File(root, "app/build/compose_compiler").path}"
    }

    private fun module(root: File, name: String, plugin: String): File = File(root, name).apply {
        mkdirs()
        File(this, "build.gradle.kts").writeText("plugins {\n    $plugin\n}\n")
    }
    private fun report(row: String): File = temporary.newFolder().apply {
        File(this, "sample-composables.csv").writeText("$Header\n$row\n")
        File(this, "sample-composables.txt").writeText("restartable skippable fun Screen()")
    }

    private companion object {
        const val Header = "package,name,composable,skippable,restartable,"
    }
}
