plugins {
    application
    alias(libs.plugins.javafx)
    alias(libs.plugins.runtime)
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
    // All three listed explicitly, not just javafx.controls: the javafx-gradle-plugin's own docs
    // say plainly that badass-runtime (used below for jpackage, since it's what supports
    // non-modular apps) needs every transitive module declared at this top level - the plugin's
    // usual auto-inclusion of javafx.controls's own javafx.graphics/javafx.base dependencies
    // doesn't propagate into badass-runtime's module resolution otherwise.
    modules = listOf("javafx.base", "javafx.graphics", "javafx.controls")
}

application {
    mainClass.set("com.loeffler.bpmcoach.desktop.MainApp")
    applicationName = "bpm-coach"
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

// SimpleJavaBLE isn't published to Maven Central (see README); the jar is
// vendored in libs/. It's also not a JPMS module (no module-info.java, and
// neither is this project's own source), which rules out the org.beryx.jlink
// plugin used here originally - that plugin's own docs say plainly to use
// Badass-Runtime for non-modular apps instead, and it fails outright without
// a module-info.java. Badass-Runtime instead resolves the runtime module set
// via jdeps against the actual classpath, which works with plain jars.
//
// NativeLibraryLoader picks its native library out of the jar's own bundled
// native/<arch>/ resources at runtime (verified by decompiling it) - it needs
// nothing from this project's resources beyond the jar itself being present
// on the packaged app's classpath, which jpackage does unconditionally.

// jlink still needs to be told where javafx's module jars physically live via --module-path -
// normally this plugin wires that up itself from the project's dependencies, but that logic
// piggybacks on the same class-scanning step that unconditionally fails below (see runtime{}),
// so the plugin ends up invoking jlink with no --module-path at all, and jlink can't resolve
// javafx.base/graphics/controls no matter what --add-modules lists. Resolved directly from the
// runtime classpath so it stays correct if the javafx version ever changes.
val javafxModulePath =
    configurations.runtimeClasspath.get().files
        .filter { it.name.startsWith("javafx-") }
        .joinToString(File.pathSeparator) { it.absolutePath }

runtime {
    options.set(
        listOf(
            "--strip-debug",
            "--compress",
            "2",
            "--no-header-files",
            "--no-man-pages",
            "--module-path",
            javafxModulePath,
        ))
    // additive=true: let the plugin's own auto-detection still run (it also seems to be what
    // wires 3rd-party dependency jars like javafx's onto jlink's module path, not just detecting
    // module names) even though it can detect nothing from OUR classes - this plugin's bundled,
    // outdated shaded ASM throws "Unsupported class file major version 70" on every one of our
    // Java 26-compiled classes (confirmed via --debug: PackageUseScanner swallows the exception
    // and just logs "Failed to scan"). additive supplements whatever it does manage to find with
    // this explicit list, derived from a direct run of the real system `jdeps` (which parses
    // major version 70 fine) against the actual compiled classes plus every runtime dependency.
    additive.set(true)
    modules.set(
        listOf(
            "java.base",
            "java.desktop",
            "java.logging",
            "java.prefs",
            "java.xml",
            "jdk.jfr",
            "javafx.base",
            "javafx.graphics",
            "javafx.controls",
        ))
    launcher {
        jvmArgs = listOf("--enable-preview")
    }
    jpackage {
        imageName = "bpm-coach"
        appVersion = project.version.toString()
        // launcher{} above covers the separate `runtime` task's distribution launcher; the
        // actual jpackage native launcher reads its own jvmArgs, and BandPoller's use of
        // StructuredTaskScope needs --enable-preview at runtime just like `run`/`test` do.
        jvmArgs = listOf("--enable-preview")
        // Portable app-image (an unzippable folder with the native launcher and a
        // bundled JRE) rather than a platform installer (.deb/.msi/.dmg) - no
        // installer tooling (WiX, etc.) needed on any of the three OSes, and no
        // installer-vs-uninstaller lifecycle to support for a one-off contest build.
        skipInstaller = true
    }
}
