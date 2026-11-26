import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.download"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.plain.download"
        minSdk = 30 // MediaStore RELATIVE_PATH/IS_PENDING without storage permission, MediaMuxer OGG output
        targetSdk = 35
        versionCode = 21
        versionName = "2.9"
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
    // No runtime dependencies at all: plain Android framework code (HttpURLConnection, MediaExtractor, MediaMuxer).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in local unit tests
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser implementation (Android ships kxml; the SDK jar is a stub)
}
