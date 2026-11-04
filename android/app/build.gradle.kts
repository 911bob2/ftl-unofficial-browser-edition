import java.util.Properties

import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The engine's own resources (fonts, shaders, UI XML under engine/resources) go into the APK as assets/engine/...,
// the names the engine reads them by (the website packs the same files into resources.pack).
// The launcher uses the website's own images (web/ftl, provenance in its README) and mod catalogue as assets/site/...
abstract class EngineAssets @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val resources: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val site: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val catalogue: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @TaskAction fun copy() {
        fs.sync {
            from(resources) { into("engine") }
            from(site) { include("honeycomb.png", "space.png", "logo.png"); into("site") }
            from(catalogue) { into("site") }
            into(output)
        }
    }
}
val engineAssets = tasks.register<EngineAssets>("engineAssets") {
    resources.set(rootProject.layout.projectDirectory.dir("../engine/resources"))
    site.set(rootProject.layout.projectDirectory.dir("../web/ftl"))
    catalogue.set(rootProject.layout.projectDirectory.file("../web/mods/catalogue.json"))
    output.set(layout.buildDirectory.dir("generated/engine-assets"))
}

android {
    namespace = "ftl.android.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ftl911bob.unofficial"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.0.3"
        // The website the app signs in to; a self-hosted instance is chosen in the launcher.
        buildConfigField("String", "DEFAULT_SERVER", "\"https://ftl.911bob.dev\"")
        // The engine build the website's saves name (PrepareAssets: engineBuild), so saves move between both.
        buildConfigField("String", "ENGINE_BUILD", "\"pocket-001b19d-browser-1\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // A release build is signed only with a key from android/signing.properties (ignored by Git: storeFile, storePassword,
    // keyAlias, keyPassword); without that file it comes out unsigned, never with the debug key.
    val signing = rootProject.file("signing.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    if (signing != null) signingConfigs.create("release") {
        storeFile = rootProject.file(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "DebugProbesKt.bin") }
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(engineAssets, EngineAssets::output) }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":mods"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.2")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
}
