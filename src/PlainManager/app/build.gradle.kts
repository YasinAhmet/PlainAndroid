import java.util.Properties

// Plain Manager (was Plain Setup until 2.0): tabs Setup (:setup), Battery (:battery, was Plain Battery) and
// Uninstaller (:uninstaller, was Plain Uninstaller). Keeps Plain Setup's package (app.plain.setup), so it updates in place.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.plain.setup"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "2.7"
    }

    // Release key shared by all Plain apps: A:/Projects/PlainSigning (keep a backup of that folder).
    val plainKey = file("A:/Projects/PlainSigning/signing.properties")
    signingConfigs {
        if (plainKey.exists()) create("plain") {
            val p = Properties().apply { plainKey.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("plain") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":setup"))
    implementation(project(":battery"))
    implementation(project(":uninstaller"))
    testImplementation("junit:junit:4.13.2")
}

// The Uninstaller tab's Shizuku library pulls in androidx.annotation (compile-time only): keep it out of the APK.
configurations.all { exclude(group = "androidx.annotation") }
