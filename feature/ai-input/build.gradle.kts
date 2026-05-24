plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.aiinput"
}

dependencies {
    implementation(projects.shared.ai)
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.data.model)
    implementation(projects.shared.domain)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)
}
