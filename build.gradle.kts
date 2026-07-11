@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("maven-publish")
    signing
}

group = "io.github.dingyi222666"
version = "1.0.3"

val runNativeHostTests = providers.gradleProperty("runNativeHostTests")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    macosX64()
    macosArm64()
    linuxArm64()
    linuxX64()
    mingwX64()

    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    webpackConfig.cssSupport {
                        enabled.set(true)
                    }
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation(kotlin("test"))

            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        jvmMain {
            dependencies {
                implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
                implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.23.1")
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
            }
            // add java to src
        }

        jsTest {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }

        nativeTest {
            dependencies {
                //  implementation(kotlin("test-native"))
            }
        }
    }

    jvmToolchain(11)
}

tasks.named("linkDebugTestMingwX64") {
    onlyIf("Enable Windows Kotlin/Native host test linking with -PrunNativeHostTests=true") {
        runNativeHostTests.get()
    }
}

tasks.named("mingwX64Test") {
    onlyIf("Enable Windows Kotlin/Native host test execution with -PrunNativeHostTests=true") {
        runNativeHostTests.get()
    }
}

// Parallel JVM tests (Windows self-hosted runner: 8 forks / 4G heap via gradle.properties)
tasks.withType<Test>().configureEach {
    maxParallelForks = 8
    // Avoid one hung suite blocking the whole fork forever without bound
    // (individual tests still use JUnit defaults unless annotated)
}

tasks.register<JavaExec>("runLuaLanguageServer") {
    group = "application"
    description = "Run the Lua language server over stdio"
    classpath = files(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    mainClass.set("io.github.dingyi222666.luaparser.lsp.LuaLanguageServerLauncherKt")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.S01)

    signAllPublications()

    coordinates("io.github.dingyi222666", "luaparser", "1.0.3")

    pom {
        name.set("luaparser")
        description.set("A Lua 5.3 Lexer & Parser written in pure Kotlin.")
        inceptionYear.set("2023")
        url.set("https://github.com/dingyi222666/luaparser")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("dingyi222666")
                name.set("dingyi222666")
                url.set("https://github.com/dingyi222666")
            }
        }
        scm {
            url.set("https://github.com/dingyi222666/lua-parser")
            connection.set("scm:git:git://github.com/dingyi222666/lua-parser.git")
            developerConnection.set("scm:git:ssh://git@github.com/dingyi222666/lua-parser.git")
        }
    }
}

