plugins {
    id("ivy.module")
    id("ivy.compose-reports")
}

android {
    // Compose
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.bundles.glance)
    implementation(libs.bundles.activity)
}
