import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.localmusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.localmusic"
        minSdk = 30 // MediaStore write/trash consent requests (Android 11+)
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    // Release key shared by all Plain apps: A:/Projects/PlainSigning (keep a backup of that folder).
    // It is the original debug key moved into its own file, so installed apps keep updating in place.
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
        debug {
            // dev build installs beside the real app with its own data
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            // Optimised build, signed with the shared Plain key (see signingConfigs) for sideloading.
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
    // No runtime dependencies at all: the app is plain Android framework code, fully offline.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
}
