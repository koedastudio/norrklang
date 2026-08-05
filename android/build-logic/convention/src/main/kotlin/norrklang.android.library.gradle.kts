import studio.koeda.norrklang.buildlogic.ProjectDefaults

// AGP 9+ has built-in Kotlin support — do not apply org.jetbrains.kotlin.android.
plugins {
    id("com.android.library")
}

android {
    compileSdk = ProjectDefaults.COMPILE_SDK

    defaultConfig {
        minSdk = ProjectDefaults.MIN_SDK
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
