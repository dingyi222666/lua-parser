plugins {
    kotlin("multiplatform").version("2.0.0-Beta3")
    java
}

group = "io.github.dingyi222666.luaparser"
version = "1.0.0"

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