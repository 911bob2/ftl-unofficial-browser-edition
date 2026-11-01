plugins { id("org.jetbrains.kotlin.jvm") }

// The website's mod merge (web/mods: ZIP reading, ftlman 0.7.4 merge and append scripts) and MEGA reader in Kotlin,
// tested against the same ground truth (tests/fixtures/mods, exported to JSON by tools/mods/kotlin-vectors.mjs).
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":savecore"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
    testImplementation("junit:junit:4.13.2")
}

val vectors = layout.buildDirectory.file("mod-vectors.json")
val modVectors = tasks.register<Exec>("modVectors") {
    val repo = rootProject.file("..")
    inputs.dir(File(repo, "tests/fixtures/mods"))
    inputs.file(File(repo, "tools/mods/kotlin-vectors.mjs"))
    inputs.dir(File(repo, "web/mods"))
    inputs.file(File(repo, "web/vendor/ftlman-lua.wasm"))
    outputs.file(vectors)
    workingDir = repo
    commandLine("node", "tools/mods/kotlin-vectors.mjs", vectors.get().asFile.absolutePath)
}
tasks.test {
    dependsOn(modVectors)
    systemProperty("vectors", vectors.get().asFile.absolutePath)
}
