pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PlainManager"
include(":app", ":setup", ":battery", ":uninstaller", ":shared")
project(":shared").projectDir = file("../PlainShared")
