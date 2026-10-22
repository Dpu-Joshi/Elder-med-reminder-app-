pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "elder-med-reminder"

// Shared alarm code: exact alarms, full-screen reminder, ringing, speech, phone checks.
include(":core-alarm")
// Throwaway app for week 1: proves the alarm rings on Xiaomi, Vivo, Oppo, Realme and Samsung.
include(":alarm-test")
// The real app: Elder mode + Family mode.
include(":app")
