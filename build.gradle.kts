plugins {
    kotlin("multiplatform") version "2.0.20"
    java
    id("com.vanniktech.maven.publish") version "0.29.0"
    id("maven-publish")
    signing
}

group = "io.github.dingyi222666"
version = "1.0.3"

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
        withJava()
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

                implementation(kotlin("test-annotations-common"))

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

