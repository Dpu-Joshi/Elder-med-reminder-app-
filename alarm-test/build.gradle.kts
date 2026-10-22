plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Week-1 throwaway: one exact alarm, one full-screen reminder, one Taken button, and a log of
// how late each alarm rang. Plain framework views, no Firebase: build it and sideload the APK.
android {
    namespace = "com.medreminder.alarmtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medreminder.alarmtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded test builds only; sign with the debug key so `assembleRelease` installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-alarm"))
}
