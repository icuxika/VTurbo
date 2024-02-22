import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT
import org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL

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
