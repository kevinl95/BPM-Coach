import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.spotless) apply false
}

allprojects {
    group = "com.loeffler.bpmcoach"
    // jpackage's macOS bundler rejects any version whose first component is 0 ("The first
    // number in an app-version cannot be zero or negative") - confirmed on real CI, Linux
    // packaged 0.1.0 fine but macOS did not. Any 0.x.y is permanently invalid there.
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("--release", "26", "--enable-preview"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<Javadoc>().configureEach {
        (options as CoreJavadocOptions).addBooleanOption("-enable-preview", true)
        (options as CoreJavadocOptions).addStringOption("-release", "26")
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat(libs.versions.google.java.format.get())
            target("src/*/java/**/*.java")
        }
    }
}
