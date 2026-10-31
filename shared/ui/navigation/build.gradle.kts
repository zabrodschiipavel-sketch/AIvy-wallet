plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.navigation"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.domain)
    implementation(projects.shared.ui.core)

    testImplementation(libs.paparazzi)
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}
