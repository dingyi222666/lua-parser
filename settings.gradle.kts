pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }


}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "luaparser"

