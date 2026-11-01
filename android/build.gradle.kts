// Plugins come from the buildscript classpath so the build resolves from the local Gradle cache (no plugin markers).
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("com.android.tools.build:gradle:9.3.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
    }
}
