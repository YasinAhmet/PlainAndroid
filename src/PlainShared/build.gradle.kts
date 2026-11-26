// Shared by every merged Plain app (Plain Manager, Plain Maps, Plain Camera, Plain Media):
// the tab header/launcher (app.plain.hub), the data import from former apps (app.plain.migrate),
// English/Turkish (app.plain.lang) and "Check for updates" (app.plain.update).
// Each app includes it with:  include(":shared"); project(":shared").projectDir = file("../PlainShared")
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
