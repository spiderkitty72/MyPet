pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.9.1"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyPet"

// The game rules live in a plain Kotlin module so they can be tested on any JVM
// and reused later by the HTML5 version. Set MYPET_CORE_ONLY=1 to build just that
// module on a machine without the Android SDK.
include(":core")
if (System.getenv("MYPET_CORE_ONLY") == null) {
    include(":app")
}
