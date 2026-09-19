import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jmailen.kotlinter") version "5.2.0"
}

group = "dev.vanssa"
version = "0.2.1"

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

    // `AgentIconService` lives in a module jar under the plugin's `lib/modules/`, which is
    // not on the classpath that `localPlugin` contributes. It is compileOnly by nature:
    // the AI Assistant plugin supplies the class at runtime, and `plugin.xml` declares the
    // dependency so the IDE refuses to load us without it.
    compileOnly(files(aiAssistantPath.map { "$it/lib/modules/intellij.ml.llm.chat.jar" }))
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
        // `AgentIconService` is internal API of the AI Assistant plugin, not a public
        // extension point contract. Pin to the 262 branch rather than pretend forward
        // compatibility we have not tested.
        ideaVersion {
            sinceBuild.set("262")
            untilBuild.set("262.*")
        }
    }

    // Nothing is published from here; signing/publishing tasks stay unconfigured.
    buildSearchableOptions.set(false)
}
