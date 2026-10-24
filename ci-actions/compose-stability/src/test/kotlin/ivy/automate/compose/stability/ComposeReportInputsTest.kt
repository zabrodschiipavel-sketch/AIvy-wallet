package ivy.automate.compose.stability

import ivy.compose.reports.composeReportFingerprint
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ComposeReportInputsTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun editSourceChangesFingerprint() {
    val root = tmp.newFolder("edit")
    val src = File(root, "src/main/Screen.kt")
    src.parentFile.mkdirs()
    src.writeText("v1")
    val before = composeReportFingerprint(root)
    src.writeText("v2")
    assertNotEquals(before, composeReportFingerprint(root))
  }

  @Test fun addSecondSourceChangesFingerprint() {
    val root = tmp.newFolder("add")
    val first = File(root, "src/main/Screen.kt")
    first.parentFile.mkdirs()
    first.writeText("v1")
    val before = composeReportFingerprint(root)
    File(root, "src/main/Other.kt").writeText("v2")
    assertNotEquals(before, composeReportFingerprint(root))
  }

  @Test fun generatedOutputsDoNotChangeFingerprint() {
    val root = tmp.newFolder("ignored")
    val src = File(root, "src/main/Screen.kt")
    src.parentFile.mkdirs()
    src.writeText("v1")
    val before = composeReportFingerprint(root)
    File(root, "build/generated/Screen.kt").apply { parentFile.mkdirs(); writeText("gen") }
    File(root, "outputs/copy/Screen.kt").apply { parentFile.mkdirs(); writeText("copy") }
    assertEquals(before, composeReportFingerprint(root))
  }
}

