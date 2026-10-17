plugins {
    java
    kotlin("jvm")
    id("org.teavm")
}
repositories { mavenCentral() }
dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jdom:jdom2:2.0.6.1")
    implementation("org.jetbrains:annotations:24.0.1")
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("org.slf4j:slf4j-jdk14:1.7.25")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.7.1")
    implementation(files("../third_party/jorbis/jorbis-core.jar"))
    implementation(teavm.libs.jso)
    implementation(teavm.libs.jsoApis)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
kotlin { jvmToolchain(17) }
sourceSets.main { java.setSrcDirs(listOf("src/main/java")) }
kotlin.sourceSets.main { kotlin.setSrcDirs(listOf("src/main/java")) }
teavm {
    all { mainClass = "ftl.browser.BrowserEngine" }
    js {
        targetFileName = "engine.js"
        sourceMap = true
        debugInformation = true
    }
}
