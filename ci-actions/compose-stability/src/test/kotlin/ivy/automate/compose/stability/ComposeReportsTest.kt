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

    private fun report(row: String): File = temporary.newFolder().apply {
        File(this, "sample-composables.csv").writeText("$Header\n$row\n")
        File(this, "sample-composables.txt").writeText("restartable skippable fun Screen()")
    }

    private companion object {
        const val Header = "package,name,composable,skippable,restartable,"
    }
}
