import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.api.tasks.testing.logging.TestLogEvent.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.beryx.runtime)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.test.junit5)

    implementation(project(":commons"))
    implementation(libs.kotlinx.cli)
}

group = "com.icuxika"
version = libs.versions.project.version.get()

application {
    mainClass.set("com.icuxika.vturbo.app.AppKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = FULL
        showExceptions = true
        showStandardStreams = true
        events(PASSED, SKIPPED, FAILED, STANDARD_OUT, STANDARD_ERROR)
    }
}
