import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from launcher/keystore.properties (never committed):
//   storeFile=/abs/path/tv-home.jks
//   storePassword=...
//   keyAlias=tvhome
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.dhruv.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dhruv.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.4.0"
    }

    signingConfigs {
        // Fixed debug key (not secret) so CI debug builds install over each other with `adb install -r`.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a private keystore, sign with the committed debug key so releases always install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        textReport = true
        // No network access to fetch newer dependency versions in CI / offline builds.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**")
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
}
