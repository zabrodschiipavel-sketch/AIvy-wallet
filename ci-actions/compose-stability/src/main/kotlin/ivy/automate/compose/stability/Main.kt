package ivy.automate.compose.stability

import ivy.automate.compose.stability.model.ComposableArgument
import ivy.automate.compose.stability.model.FullyQualifiedName
import ivy.automate.compose.stability.model.UnstableComposable
import ivy.compose.reports.composeReportFingerprint
import java.io.File
import kotlin.system.exitProcess

const val OutputReportFileName = "ivy-compose-stability-report.txt"
const val ComposeReportFolderName = "compose_full"
const val CoverageFileName = "ivy-compose-coverage.tsv"
const val BaselineArg = "generateBaseline"
const val BaselineFileName = "ivy-compose-stability-baseline.txt"

fun main(args: Array<String>) {
    listOf(OutputReportFileName, CoverageFileName).forEach { path ->
        val output = File(path)
        check(!output.exists() || output.delete()) { "Cannot clear previous Compose result: $output" }
    }
    val shouldGenerateBaseline = BaselineArg in args
    val baselineComposables = readBaseline()
    val expectedModules = System.getenv("IVY_COMPOSE_REPORT_MODULES")
        ?.lineSequence()?.filter { it.isNotBlank() }?.toSet()
    val unstableComposables = readFullComposeReports(File("../../"), expectedModules)
        .filter {
            it.isNotViewModelFunction() &&
                (shouldGenerateBaseline || it.fullyQualifiedName !in baselineComposables)
        }
    val ivyReportTxt = buildIvyReport(unstableComposables)
    createReportFile(ivyReportTxt)
    println(ivyReportTxt)
    if (!shouldGenerateBaseline) {
        if (unstableComposables.isNotEmpty()) {
            println("ERROR: ${unstableComposables.size} non-skippable restartable composables found.")
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

private val ComposePluginDeclarations = setOf(
    "id(\"ivy.feature\")",
    "id(\"ivy.compose\")",
    "id(\"ivy.widget\")",
    "id(\"ivy.compose-reports\")",
    "id(\"ivy.paparazzi\")",
    "id(\"org.jetbrains.kotlin.plugin.compose\")",
    "org.jetbrains.kotlin.plugin.compose",
)
private val IgnoredDirectories = setOf("build", ".git", ".gradle", ".idea", "outputs", ".kotlin", ".codegraph")

internal fun findComposeReportFolders(root: File): Sequence<File> {
    // The quality workflow builds assembleDemo. Derive expected reports from
    // plugin declarations, not existing reports: skipped modules must not pass.
    return root.walkTopDown()
        .onEnter { it.name !in IgnoredDirectories }
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .filter { buildFile ->
            val declarations = buildFile.readLines().map { it.substringBefore("//") }
            declarations.any { line -> ComposePluginDeclarations.any { it in line } }
        }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .map { File(it.parentFile, "build/$ComposeReportFolderName") }
}

internal fun readFullComposeReports(
    root: File,
    expectedModules: Set<String>? = null,
): List<UnstableComposable> {
    val folders = findComposeReportFolders(root).toList()
    check(folders.isNotEmpty()) { "No Compose modules found." }
    val discovered = folders.map { it.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath }.toSet()
    check(expectedModules == null || expectedModules == discovered) {
        val missing = (expectedModules.orEmpty() - discovered).sorted()
        val extra = (discovered - expectedModules.orEmpty()).sorted()
        "Compose discovery differs from Gradle: missing=$missing, extra=$extra"
    }
    val fingerprint = composeReportFingerprint(root)
    val sessions = folders.map { verifyReportProvenance(it, fingerprint) }.toSet()
    check(sessions.size == 1) { "Compose reports belong to different full compilations." }
    val expectedSession = System.getenv("IVY_COMPOSE_REPORT_SESSION")
    check(expectedSession == null || sessions.single() == expectedSession) {
        "Compose reports are from an earlier build."
    }
    val unstable = readComposeReports(folders, productionOnly = true)
    val coverage = buildString {
        append("# session=${sessions.single()} sourcesSha256=$fingerprint\n")
        append("module\tvariant\tcomposables\n")
        folders.forEach { folder ->
            val csv = productionReport(folder)
            val count = parseComposables(csv).size
            append("${folder.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath}\t")
            append("${if (folder.parentFile.parentFile.name == "app") "demo" else "release"}\t$count\n")
        }
    }
    File(CoverageFileName).writeText(coverage)
    println("Verified full Compose reports for ${folders.size} modules.")
    return unstable
}

internal fun readComposeReports(
    reportFolders: List<File>,
    productionOnly: Boolean = false,
): List<UnstableComposable> {
    check(reportFolders.isNotEmpty()) { "No Compose compiler report folders found. Build the candidate first." }
    var composableCount = 0
    val unstable = reportFolders.flatMap { folder ->
        val csvFiles = if (productionOnly) {
            listOf(productionReport(folder)).filter { it.isFile }
        } else {
            folder.listFiles()?.filter { it.name.endsWith("-composables.csv") }.orEmpty()
        }
        check(csvFiles.isNotEmpty()) { "No composables CSV reports in ${folder.path}" }
        csvFiles.flatMap { csv ->
            val textFile = File(folder, csv.name.removeSuffix(".csv") + ".txt")
            check(textFile.isFile) { "Missing companion report ${textFile.path}" }
            val text = textFile.readText()
            val composables = parseComposables(csv)
            check(composables.isEmpty() || "fun " in text) { "Invalid companion report ${textFile.path}" }
            composableCount += composables.size
            // Only restartable functions are eligible for skipping. Value-returning
            // and inline helpers remain in the coverage report without failing this check.
            composables.filter { it.restartable && !it.skippable }.map {
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
        val skippable = flag("skippable")
        val restartable = flag("restartable")
        check(!skippable || restartable) { "A skippable composable must be restartable in ${csv.path}" }
        UnstableComposable(
            fullyQualifiedName = field("package"),
            name = field("name"),
            skippable = skippable,
            restartable = restartable,
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
    append("NON-SKIPPABLE RESTARTABLE COMPOSABLES:")
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
    append("Non-skippable restartable composables: ${unstableComposables.size}")
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
