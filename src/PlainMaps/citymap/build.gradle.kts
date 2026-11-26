// Plain Maps' city map: the Istanbul tab (main process) and the Ankara tab (":ankara" process), see City.kt.
// Until Plain Maps 3.0 this was the "istanbul"/"ankara" product flavors of one app. Built into :app.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.plain.maps"
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

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true } // the real-data checks print a report
    maxHeapSize = "3g"
    // real-data checks read the files named by these variables (skipped when unset)
    listOf("GTFS_DIR", "OSM_CELL", "OSM_PBF", "IETT_PLAN", "ANKARA_PBF", "EGO_DIR").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}

dependencies {
    implementation(project(":shared"))
    testImplementation("junit:junit:4.13.2")
}
