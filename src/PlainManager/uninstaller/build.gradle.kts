// Tab module of Plain Manager (was its own app until Plain Manager 2.0). Built into :app; see ../app.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.uninstaller"
    compileSdk = 35
    defaultConfig { minSdk = 26; consumerProguardFiles("proguard-rules.pro") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Shizuku is the one library Plain Manager uses (Uninstaller tab). Its only transitive extra is androidx.annotation
// (compile-time annotations); it is left out so the app stays AndroidX-free.
configurations.all { exclude(group = "androidx.annotation") }

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
}
