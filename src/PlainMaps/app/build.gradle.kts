import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.maps"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.plain.maps"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"
    }

    buildFeatures { buildConfig = true }

    // One app per city. Istanbul keeps the original package, so the installed app updates in place.
    flavorDimensions += "city"
    productFlavors {
        create("istanbul") {
            dimension = "city"
            applicationId = "app.plain.maps"
            buildConfigField("String", "CITY", "\"istanbul\"")
            resValue("string", "app_name", "Plain Istanbul Map")
            resValue("color", "ic_bg", "#0E2A33")
        }
        create("ankara") {
            dimension = "city"
            applicationId = "app.plain.maps.ankara"
            buildConfigField("String", "CITY", "\"ankara\"")
            resValue("string", "app_name", "Plain Ankara Map")
            resValue("color", "ic_bg", "#33200E")
        }
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

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true } // the real-data checks print a report
    maxHeapSize = "3g"
    // real-data checks read the files named by these variables (skipped when unset)
    listOf("GTFS_DIR", "OSM_CELL", "OSM_PBF", "IETT_PLAN", "ANKARA_PBF", "EGO_DIR").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
