import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jmailen.kotlinter") version "5.2.0"
}

group = "dev.vanssa"
version = "0.3.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// JetBrains AI Assistant is NOT bundled inside the IDE installation — it is installed
// per-IDE under the config directory and updates on its own cadence (262.8665.344 against
// an IDE build of 262.8665.325). So `bundledPlugin("com.intellij.ml.llm")` cannot find it.
val aiAssistantPath: Provider<String> = providers.gradleProperty("aiAssistantPluginPath")

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("platformLocalPath"))
        localPlugin(aiAssistantPath)
    }

    // Plain JUnit 5, not the platform test framework: the tests cover the pure parsing
    // and list logic, which needs Gson from the platform classpath but no running IDE.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// No jvmToolchain(): the only JDK on this machine is the JetBrains Runtime (25), and
// toolchain auto-provisioning would drag in a second JDK. Compile with whatever JDK
// Gradle runs on, but emit bytecode the 2026.2 platform (JVM 21) can load.
//
// Configured per task rather than through the `kotlin { compilerOptions { } }` extension:
// the extension-level value gets overwritten later in the build and compileKotlin ends up
// defaulting to the JDK's own version, which then fails Kotlin's target-consistency check
// against compileJava.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        // No AI Assistant classes are used, but the plugin still relies on how AI Assistant
        // reads `~/.jetbrains/acp.json` and launches local agents, which is IDE behaviour
        // rather than a published contract. Cover only the branches the plugin has been checked
        // on rather than pretend forward compatibility we have not tested. 263 was added after
        // the plugin verifier passed against IU-263.6259.32 (2026.3 EAP) and the `acp.json`
        // schema bundled with AI Assistant 263 matched 262's.
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set("263.*")
        }
    }

    // Nothing is published from here; signing/publishing tasks stay unconfigured.
    buildSearchableOptions.set(false)
}
