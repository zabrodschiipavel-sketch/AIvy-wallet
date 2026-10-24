package ivy.automate.compose.stability

import ivy.compose.reports.composeFileSha256
import java.io.File
import java.util.Properties

internal fun productionReport(folder: File): File {
    val module = folder.parentFile.parentFile
    val variant = if (module.name == "app") "demo" else "release"
    return File(folder, "${module.name}_${variant}-composables.csv")
}

internal fun verifyReportProvenance(folder: File, sourceFingerprint: String): String {
    val marker = File(folder, "full-compilation.properties")
    check(marker.isFile) { "Missing full-compilation marker: $marker. Run with -PivyComposeFullReports=true." }
    val properties = Properties().apply { marker.inputStream().use { load(it) } }
    val csv = productionReport(folder)
    val text = File(folder, csv.name.removeSuffix(".csv") + ".txt")
    val variant = if (folder.parentFile.parentFile.name == "app") "demo" else "release"
    check(properties.getProperty("format") == "1") { "Unsupported Compose marker: $marker" }
    check(properties.getProperty("variant") == variant && properties.getProperty("incremental") == "false") {
        "Not a full $variant compilation: $marker"
    }
    check(properties.getProperty("sourcesSha256") == sourceFingerprint) { "Stale Compose sources: $marker" }
    check(csv.isFile && text.isFile) { "Missing production Compose reports: $csv / $text" }
    check(properties.getProperty("csvSha256") == composeFileSha256(csv)) { "Changed Compose CSV: $csv" }
    check(properties.getProperty("textSha256") == composeFileSha256(text)) { "Changed Compose text report: $text" }
    val session = properties.getProperty("session").orEmpty()
    check(session.isNotBlank()) { "Missing Compose compilation session: $marker" }
    return session
}
