import ivy.compose.reports.composeFileSha256
import ivy.compose.reports.composeReportFingerprint
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties
import java.util.UUID

plugins {
    org.jetbrains.kotlin.plugin.compose
}

val fullReports = providers.gradleProperty("ivyComposeFullReports").orNull == "true"
val reportDirectory = layout.buildDirectory.dir(if (fullReports) "compose_full" else "compose_compiler")

composeCompiler {
    reportsDestination = reportDirectory
    metricsDestination = reportDirectory
}

if (fullReports) {
    val shared = rootProject.extensions.extraProperties
    if (!shared.has("ivyComposeReportSession")) {
        shared.set("ivyComposeReportSession", UUID.randomUUID().toString())
        shared.set("ivyComposeSourcesSha256", composeReportFingerprint(rootDir))
    }
    val session = shared.get("ivyComposeReportSession").toString()
    val sourcesSha256 = shared.get("ivyComposeSourcesSha256").toString()
    val variant = if (plugins.hasPlugin("com.android.application")) "demo" else "release"
    val compileTask = "compile${variant.replaceFirstChar { it.uppercaseChar() }}Kotlin"
    val moduleName = project.name
    tasks.withType<KotlinCompile>().configureEach {
        if (name == compileTask) {
            incremental = false
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Full Compose reports require a fresh compiler invocation") { true }
            doFirst {
                val folder = reportDirectory.get().asFile
                check(!folder.exists() || folder.deleteRecursively()) { "Cannot clear Compose reports: $folder" }
            }
            doLast {
                check(!incremental) { "Full Compose reports require nonincremental compilation" }
                val folder = reportDirectory.get().asFile
                val csv = folder.resolve("${moduleName}_${variant}-composables.csv")
                val text = folder.resolve("${moduleName}_${variant}-composables.txt")
                check(csv.isFile && text.isFile) { "Missing full Compose reports in $folder" }
                val marker = Properties().apply {
                    setProperty("format", "1")
                    setProperty("variant", variant)
                    setProperty("incremental", "false")
                    setProperty("session", session)
                    setProperty("sourcesSha256", sourcesSha256)
                    setProperty("csvSha256", composeFileSha256(csv))
                    setProperty("textSha256", composeFileSha256(text))
                }
                folder.resolve("full-compilation.properties").outputStream().use {
                    marker.store(it, "Completed full Compose compilation")
                }
            }
        }
    }
}
