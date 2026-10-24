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
        val composeProjects = rootProject.subprojects.filter { it.plugins.hasPlugin("ivy.compose-reports") }
        tasks.named<JavaExec>("run") {
            dependsOn(composeProjects.map { module ->
                val variant = if (module.plugins.hasPlugin("com.android.application")) "Demo" else "Release"
                module.tasks.named("compile${variant}Kotlin")
            })
            environment("IVY_COMPOSE_REPORT_SESSION", rootProject.extra["ivyComposeReportSession"].toString())
        }
    }
}

dependencies {
    implementation(projects.ciActions.base)
}
