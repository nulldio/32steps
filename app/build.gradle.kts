import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.thirtytwo.steps"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.thirtytwo.steps"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "2.2.6"
    }

    androidResources {
        noCompress += "gz"
    }

    if (file("../release-key.jks").exists()) {
        val props = Properties()
        val localProps = file("../local.properties")
        if (localProps.exists()) props.load(localProps.inputStream())

        signingConfigs {
            create("release") {
                storeFile = file("../release-key.jks")
                storePassword = props.getProperty("STORE_PASSWORD", "")
                keyAlias = props.getProperty("KEY_ALIAS", "")
                keyPassword = props.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (file("../release-key.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
}
