plugins { id("org.jetbrains.kotlin.jvm") }

// The shared save and sync rules (../../savecore); the browser build compiles the same sources with TeaVM.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.setSrcDirs(listOf("../../savecore/src/main/kotlin"))
    sourceSets["test"].kotlin.setSrcDirs(listOf("../../savecore/src/test/kotlin"))
}
dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { systemProperty("vectors", rootProject.file("../savecore/vectors").absolutePath) }
