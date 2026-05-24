plugins {
    id("ivy.module")
}

android {
    namespace = "com.ivy.ai"
}

dependencies {
    implementation(projects.shared.base)

    implementation(libs.bundles.ktor)
    implementation(libs.androidx.security)
}
