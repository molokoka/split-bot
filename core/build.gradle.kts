plugins {
    kotlin("jvm") version "2.4.0"
}

repositories {
    mavenCentral()
}

val kotestVersion = "5.9.1"

dependencies {
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
