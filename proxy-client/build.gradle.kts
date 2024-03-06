import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.beryx.runtime)
    alias(libs.plugins.graalvm.native)
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
    implementation(libs.bundles.log4j)
}

group = "com.icuxika"
version = libs.versions.project.version.get()

application {
    application.applicationName = "ProxyClient"
    mainClass.set("com.icuxika.vturbo.client.AppKt")
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

// .\gradlew.bat :proxy-client:nativeCompile
// 执行命令前将 dependencies 块中的 implementation(libs.bundles.log4j) 换成 implementation(libs.bundles.logback)
//GraalVM 构建，尚不支持Log4j，换成logback可以构建，但是没有日志输出，而Spring Initializer生成的SpringBoot项目添加logback-spring.xml然后进行native构建确实支持的，以后再尝试
graalvmNative {
    binaries {
        all {
            resources.autodetect()
        }
        named("main") {
            imageName.set(application.applicationName)
            mainClass.set(application.mainClass)
        }
    }
    binaries.all {
        buildArgs.add("--verbose")
        buildArgs.add("--report-unsupported-elements-at-runtime")
        buildArgs.add("--no-fallback")
    }
}
