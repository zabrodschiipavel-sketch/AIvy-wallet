plugins {
    id("ivy.module")
    id("ivy.room")
}

android {
    namespace = "com.ivy.data.testing"
}

dependencies {
    implementation(projects.shared.data.core)
}
