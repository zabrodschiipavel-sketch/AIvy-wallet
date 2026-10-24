plugins {
    id("ivy.script")
    application
}

application {
    mainClass = "ivy.automate.compose.stability.MainKt"
}

// Compile the same input-fingerprint contract as the Gradle report producer.
kotlin.sourceSets.named("main") {
    kotlin.srcDir(rootProject.file("buildSrc/src/main/kotlin/ivy/compose/reports"))
}

if (providers.gradleProperty("ivyComposeFullReports").orNull == "true") {
    gradle.projectsEvaluated {
        val composeProjects = rootProject.subprojects.filter { it.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose") }
        val missingConvention = composeProjects.filterNot { it.plugins.hasPlugin("ivy.compose-reports") }
        check(missingConvention.isEmpty()) { "Missing ivy.compose-reports convention in Compose modules: ${missingConvention.map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath }.sorted().joinToString(", ")}" }
        tasks.named<JavaExec>("run") {
            dependsOn(
                composeProjects.map { module ->
                    val variant = if (module.plugins.hasPlugin("com.android.application")) "Demo" else "Release"
                    module.tasks.named("compile${variant}Kotlin")
                }
            )
            environment("IVY_COMPOSE_REPORT_SESSION", rootProject.extra["ivyComposeReportSession"].toString())
            environment("IVY_COMPOSE_REPORT_MODULES", composeProjects.map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath }.sorted().joinToString("\n"))
        }
    }
}

dependencies {
    implementation(projects.ciActions.base)
}
