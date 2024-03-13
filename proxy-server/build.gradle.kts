import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.beryx.runtime)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)

    implementation(libs.kotlinx.cli)
    implementation(libs.kotlinx.coroutines.core)

    implementation(project(":commons"))
    implementation(libs.bundles.logback)
}

group = "com.icuxika"
version = libs.versions.project.version.get()

application {
    applicationName = "ProxyServer"
    mainClass.set("com.icuxika.vturbo.server.AppKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dkotlinx.coroutines.debug", "-Dkeys.path=../keys/")
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        noConsole = false
    }

    val currentOS = OperatingSystem.current()
    jpackage {
        imageName = application.applicationName
        appVersion = version.toString()
        jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dkeys.path=keys/")
        if (currentOS.isWindows) {
            imageOptions.addAll(listOf("--win-console"))
        }
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set(application.applicationName)
            buildArgs.add("--verbose")
            buildArgs.add("--report-unsupported-elements-at-runtime")
            buildArgs.add("--no-fallback")
        }
    }
}