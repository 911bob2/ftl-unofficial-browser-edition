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

// TeaVM's runtime fills a Java exception's stack through an optional decoder only when a global of the decoder's name is
// a function (`typeof $rt_decodeStack === "function"`). The minifier renames that undeclared global to a short name like
// any other, and a browser global of the same name then answers the check: one build named it HID, Chromium's WebHID
// interface, and every exception threw "Illegal constructor" (the game did not start). No page defines the decoder, so
// the check is made false, padded to its own length so the source map's columns stay right.
tasks.named("generateJavaScript") {
    doLast {
        val file = layout.buildDirectory.file("generated/teavm/js/engine.js").get().asFile
        val text = file.readText()
        val check = Regex("""typeof ([A-Za-z_$][A-Za-z0-9_$]*)==="function"(&&err\.stack\)\{let stack=)\1\(err\.stack\)""")
        val match = check.find(text)
        if (match == null) {
            logger.warn("engine.js: TeaVM's stack decoder check not found; not patched")
        } else {
            val head = "typeof ${match.groupValues[1]}===\"function\""
            file.writeText(text.replaceRange(match.range.first, match.range.first + head.length, "!1".padEnd(head.length)))
            logger.lifecycle("engine.js: TeaVM's stack decoder check (global ${match.groupValues[1]}) made false")
        }
    }
}
