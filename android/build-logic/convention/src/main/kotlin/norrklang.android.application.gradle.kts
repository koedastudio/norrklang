import java.util.Properties
import studio.koeda.norrklang.buildlogic.ProjectDefaults

// AGP 9+ has built-in Kotlin support — do not apply org.jetbrains.kotlin.android.
plugins {
    id("com.android.application")
}

// Single per-release knob: norrklang.version in android/gradle.properties.
val appVersion = providers.gradleProperty("norrklang.version").get()

android {
    compileSdk = ProjectDefaults.COMPILE_SDK

    defaultConfig {
        minSdk = ProjectDefaults.MIN_SDK
        targetSdk = ProjectDefaults.TARGET_SDK
        versionName = ProjectDefaults.versionName(appVersion)
        versionCode = ProjectDefaults.versionCode(appVersion, project.name)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing from android/keystore.properties (gitignored;
    // storeFile/storePassword/keyAlias/keyPassword for the upload keystore).
    // Without it, release tasks fail fast (requireReleaseSigning below) —
    // deliberately no debug-key fallback for release artifacts.
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use(::load) }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Embed native debug symbols (androidx ships .so files) so Play can
            // symbolicate native crashes and stops warning at upload.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// Fail any release variant up front when keystore.properties is missing —
// release artifacts must never be built unsigned or debug-keyed by accident.
val keystoreFile = rootProject.file("keystore.properties")
if (!keystoreFile.exists()) {
    // Capture only plain values — a script object reference would break the
    // configuration cache.
    val message = "Release signing is not configured: ${keystoreFile.path} is missing. " +
        "Create it (gitignored) with storeFile=<path-to-upload-keystore.jks>, " +
        "storePassword=…, keyAlias=…, keyPassword=…."
    val guard = tasks.register("requireReleaseSigning") {
        doFirst { throw GradleException(message) }
    }
    tasks.configureEach {
        if (name == "assembleRelease" || name == "bundleRelease") {
            dependsOn(guard)
        }
    }
}
