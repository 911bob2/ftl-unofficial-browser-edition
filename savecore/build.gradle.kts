// Website build (Docker, Gradle 8.7): savecore.js from the shared save rules plus the JS export class. The Android
// build compiles the same src/main/kotlin through android/savecore.
plugins {
    kotlin("jvm")
    id("org.teavm")
}
repositories { mavenCentral() }
dependencies {
    implementation(kotlin("stdlib"))
    implementation(teavm.libs.jso)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }
sourceSets.main { java.setSrcDirs(listOf("src/js/java")) }
sourceSets.test { java.setSrcDirs(emptyList<String>()) }
kotlin.sourceSets.main { kotlin.setSrcDirs(listOf("src/main/kotlin", "src/js/java")) }
kotlin.sourceSets.test { kotlin.setSrcDirs(emptyList<String>()) }
teavm {
    all { mainClass = "ftl.saves.js.SaveCoreJs" }
    js {
        targetFileName = "savecore.js"
        sourceMap = true
    }
}
