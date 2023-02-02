plugins {
    kotlin("jvm") version "1.8.0"
    java
    application
}

// do the same for group
group = "io.githubdingyi222666"
version = "1.0-SNAPSHOT"


// you can also use a jitpack version:
//val antlrKotlinVersion = "86a86f1968"

repositories {
    // used for local development and while building by travis ci and jitpack.io
    mavenLocal()
    // used to download antlr4
    mavenCentral()
    // used to download antlr-kotlin-runtime
    maven("https://jitpack.io")
}



dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation ("com.google.code.gson:gson:2.10.1")
    testImplementation(kotlin("test-junit5"))

}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}