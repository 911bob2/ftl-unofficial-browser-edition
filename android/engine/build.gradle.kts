import javax.inject.Inject

plugins { id("com.android.library") }

// The engine sources minus the files that only exist for the TeaVM browser build. Each excluded host class has an
// Android twin with the same public API in src/main/java; the engine core itself is compiled unchanged.
val browserOnly = listOf(
    "android/opengl/GLES30.java", "android/util/Log.java",
    "ftl/browser/BrowserAudio.java", "ftl/browser/BrowserCheckpoints.java", "ftl/browser/BrowserClock.java",
    "ftl/browser/BrowserDebug.java", "ftl/browser/BrowserEngine.java", "ftl/browser/BrowserFiles.java",
    "ftl/browser/BrowserHeap.java", "ftl/browser/BrowserHsScan.java", "ftl/browser/BrowserInflater.java",
    "ftl/browser/BrowserJsStack.java", "ftl/browser/BrowserMods.java", "ftl/browser/BrowserPixels.java",
    "ftl/browser/BrowserXml.java", "xyz/znix/xftl/game/LanguageJs.java", "xyz/znix/xftl/hs/lua/LuaJs.java",
    // Browser test probes (driven from the website's test runner) and Slipstream's unused JSON catalogue readers.
    "ftl/browser/*Diagnostics.kt", "ftl/browser/Gameplay*Checks.kt", "ftl/browser/BrowserParityGeneration.kt",
    "ftl/browser/BrowserCanvasShot.java",
    "xyz/znix/xftl/hs/lua/LuaProbe.kt",
    "net/vhati/modmanager/json/**", "net/vhati/modmanager/core/HashThread.java",
    "net/vhati/modmanager/core/ModsScanThread.java",
)

abstract class EngineSources @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val engine: DirectoryProperty
    @get:Input abstract val excludes: ListProperty<String>
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun copy() {
        val skip = excludes.get()
        fs.sync {
            from(engine) { exclude(skip); include("**/*.java", "**/*.kt") }
            into(output)
        }
    }
}
val engineSources = tasks.register<EngineSources>("engineSources") {
    engine.set(rootProject.layout.projectDirectory.dir("../engine/src/main/java"))
    excludes.set(browserOnly)
    output.set(layout.buildDirectory.dir("generated/engine-src"))
}

android {
    namespace = "ftl.android.engine"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        // libhslua.so: the Hyperspace Lua runtime (../hs-lua + Lua 5.3.6) with its JNI host, src/main/jni
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        consumerProguardFiles("src/main/jni/jni-keep.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(engineSources, EngineSources::output)
        variant.sources.kotlin?.addGeneratedSourceDirectory(engineSources, EngineSources::output)
    }
}
// The engine is written for Kotlin 1.9 (the TeaVM build); stdlib calls deprecated since then stay warnings here.
kotlin {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        suppressWarnings.set(true)
    }
}
dependencies {
    api(project(":savecore"))
    api("org.jdom:jdom2:2.0.6")
    implementation("org.slf4j:slf4j-api:1.7.30")
    api(files("../../third_party/jorbis/jorbis-core.jar"))
    compileOnly("org.jetbrains:annotations:23.0.0")
    // instrumented tests of the native runtime (HsLuaNativeTest); ext.junit pins the monitor/storage versions in the
    // cache, the constraints lift the older transitive versions the offline cache does not hold
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    constraints {
        androidTestImplementation("androidx.annotation:annotation:1.9.1")
        androidTestImplementation("androidx.annotation:annotation-experimental:1.4.1")
        androidTestImplementation("androidx.lifecycle:lifecycle-common:2.9.4")
        androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    }
}
