enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    // Auto-provisions a matching JDK toolchain (e.g. JDK 17) when one isn't installed locally.
    // See https://github.com/gradle/foojay-toolchains
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        gradlePluginPortal()
    }
}

rootProject.name = "IvyWallet"
include(":app")
include(":ci-actions:base")
include(":ci-actions:compose-stability")
include(":ci-actions:issue-assign")
include(":ci-actions:issue-create-comment")
include(":ci-actions:pr-description-check")
include(":feature:accounts")
include(":feature:attributions")
include(":feature:balance")
include(":feature:budgets")
include(":feature:categories")
include(":feature:contributors")
include(":feature:disclaimer")
include(":feature:edit-transaction")
include(":feature:exchange-rates")
include(":feature:features")
include(":feature:home")
include(":feature:import-data")
include(":feature:loans")
include(":feature:main")
include(":feature:onboarding")
include(":feature:piechart")
include(":feature:planned-payments")
include(":feature:poll:impl")
include(":feature:poll:public")
include(":feature:releases")
include(":feature:reports")
include(":feature:search")
include(":feature:settings")
include(":feature:transactions")
include(":shared:ai")
include(":shared:base")
include(":shared:data:core")
include(":shared:data:core-testing")
include(":shared:data:model")
include(":shared:data:model-testing")
include(":shared:domain")
include(":shared:ui:core")
include(":shared:ui:navigation")
include(":shared:ui:testing")
include(":temp:legacy-code")
include(":temp:old-design")
include(":widget:add-transaction")
include(":widget:balance")
include(":widget:shared-base")