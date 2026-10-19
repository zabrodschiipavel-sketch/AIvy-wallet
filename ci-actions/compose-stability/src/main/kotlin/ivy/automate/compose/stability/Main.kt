package ivy.automate.compose.stability

import ivy.automate.compose.stability.model.ComposableArgument
import ivy.automate.compose.stability.model.FullyQualifiedName
import ivy.automate.compose.stability.model.UnstableComposable
import java.io.File
import kotlin.system.exitProcess

const val OutputReportFileName = "ivy-compose-stability-report.txt"
const val ComposeReportFolderName = "compose_compiler"
const val BaselineArg = "generateBaseline"
const val BaselineFileName = "ivy-compose-stability-baseline.txt"

fun main(args: Array<String>) {
    val shouldGenerateBaseline = BaselineArg in args
    val baselineComposables = readBaseline()
    val unstableComposables = readComposeReports(findComposeReportFolders().toList())
        .filter {
            it.isNotViewModelFunction() &&
                    (shouldGenerateBaseline || it.fullyQualifiedName !in baselineComposables)
        }
    val ivyReportTxt = buildIvyReport(unstableComposables)
    createReportFile(ivyReportTxt)
    println(ivyReportTxt)
    if (!shouldGenerateBaseline) {
        if (unstableComposables.isNotEmpty()) {
            println("ERROR: ${unstableComposables.size} unstable composables found. Fix them!")
            exitProcess(1)
        } else {
            println("SUCCESS!")
        }
    } else {
        generateBaseline(unstableComposables)
        println("Baseline generated.")
        println("Check: ${File(BaselineFileName).absolutePath}")
    }
}

private fun findComposeReportFolders(): Sequence<File> {
    val rootDirRelativePath = "../../"
    // The quality workflow builds assembleRelease for all modules. Derive the
    // expected reports from the convention plugin instead of accepting whichever
    // report folders happen to exist (a skipped module must not silently pass).
    return File(rootDirRelativePath).walkTopDown()
        .onEnter { it.name !in setOf("build", ".git", ".gradle", ".idea") }
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .filter { "id(\"ivy.feature\")" in it.readText() || "id(\"ivy.compose\")" in it.readText() }
        .map { File(it.parentFile, "build/$ComposeReportFolderName") }
}

internal fun readComposeReports(reportFolders: List<File>): List<UnstableComposable> {
    check(reportFolders.isNotEmpty()) { "No Compose compiler report folders found. Build the candidate first." }
    var composableCount = 0
    val unstable = reportFolders.flatMap { folder ->
        val csvFiles = folder.listFiles()?.filter { it.name.endsWith("-composables.csv") }.orEmpty()
        check(csvFiles.isNotEmpty()) { "No composables CSV reports in ${folder.path}" }
        csvFiles.flatMap { csv ->
            val textFile = File(folder, csv.name.removeSuffix(".csv") + ".txt")
            check(textFile.isFile) { "Missing companion report ${textFile.path}" }
            val text = textFile.readText()
            val composables = parseComposables(csv)
            check(composables.isEmpty() || "fun " in text) { "Invalid companion report ${textFile.path}" }
            composableCount += composables.size
            composables.filter { !it.restartable || !it.skippable }.map {
                it.copy(unstableArguments = it.findUnstableArguments(text).toSet())
            }
        }
    }
    check(composableCount > 0) { "Compose reports contain no composables; cannot validate the candidate." }
    return unstable
}

private fun parseComposables(csv: File): List<UnstableComposable> {
    val lines = csv.readLines()
    check(lines.isNotEmpty()) { "Empty Compose report ${csv.path}" }
    val header = lines.first().split(',')
    val fields = listOf("package", "name", "skippable", "restartable")
    check(fields.all { field -> header.count { it == field } == 1 }) {
        "Unsupported Compose CSV header in ${csv.path}"
    }
    return lines.drop(1).filter { it.isNotBlank() }.mapIndexed { index, row ->
        val values = row.split(',')
        check(values.size == header.size) { "Invalid column count at ${csv.path}:${index + 2}" }
        fun field(name: String) = values[header.indexOf(name)]
        check(field("package").isNotBlank() && field("name").isNotBlank()) {
            "Missing composable identity at ${csv.path}:${index + 2}"
        }
        fun flag(name: String): Boolean {
            val value = field(name)
            check(value == "0" || value == "1") { "Invalid $name at ${csv.path}:${index + 2}" }
            return value == "1"
        }
        UnstableComposable(
            fullyQualifiedName = field("package"),
            name = field("name"),
            skippable = flag("skippable"),
            restartable = flag("restartable"),
            unstableArguments = emptySet(),
        )
    }
}

private fun UnstableComposable.findUnstableArguments(
    composablesTxt: String
): List<ComposableArgument> {
    val composableFunction = composablesTxt.split(")\n").firstOrNull { funTxt ->
        "fun $name(" in funTxt
    } ?: return emptyList()
    return composableFunction.split("\n")
        .drop(1) // drop the signature
        .mapNotNull { paramTxt ->
            try {
                if ("unstable" in paramTxt) {
                    val words = paramTxt.split(" ")
                        .filter { it.isNotBlank() }
                    ComposableArgument(
                        name = words[1].dropLast(1), // drop the ":"
                        type = words[2]
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
}

private fun buildIvyReport(
    unstableComposables: List<UnstableComposable>
): String = buildString {
    append("-----------")
    append("\n")
    append("UNSTABLE COMPOSABLES:")
    append("\n")
    unstableComposables.forEachIndexed { index, composable ->
        append("@Composable ${composable.fullyQualifiedName}")
        append("(restartable = ${composable.restartable}, skippable = ${composable.skippable}):\n")
        composable.unstableArguments.forEach { arg ->
            append("-unstable ")
            append("\"${arg.name}: ${arg.type}\"")
            append("\n")
        }
        if (index != unstableComposables.lastIndex) {
            append("\n")
        }
    }
    append("-----------\n")
    append("[CONCLUSION]\n")
    append("Unstable Composables: ${unstableComposables.size}")
}

private fun createReportFile(report: String) {
    val reportFile = File(OutputReportFileName)
    reportFile.writeText(report)
}

private fun generateBaseline(unstableComposables: List<UnstableComposable>) {
    val baselineContent = unstableComposables.joinToString(separator = "\n") {
        it.fullyQualifiedName
    }
    val baselineFile = File(BaselineFileName)
    baselineFile.writeText(baselineContent)
}

private fun readBaseline(): Set<FullyQualifiedName> {
    return File(BaselineFileName).readLines().filter { it.isNotBlank() }.toSet()
}

private fun UnstableComposable.isNotViewModelFunction(): Boolean {
    return "ViewModel." !in fullyQualifiedName
}
