plugins {
    application
    alias(libs.plugins.javafx)
    alias(libs.plugins.jlink)
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/simplejavable-1.0.0.jar"))

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

javafx {
    version = libs.versions.javafxVersion.get()
    modules = listOf("javafx.controls")
}

application {
    mainClass.set("com.loeffler.bpmcoach.desktop.MainApp")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

// SimpleJavaBLE isn't published to Maven Central (see README); the jar is
// vendored in libs/. Native libs live under src/main/resources/native/<os>/
// so NativeLibraryLoader (inside the jar) can find them on the classpath.
jlink {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "bpm-coach"
    }
    forceMerge("javafx")
    mergedModule {
        additive = true
        requires("java.desktop")
        requires("java.logging")
    }
}
