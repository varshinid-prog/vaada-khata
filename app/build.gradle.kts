plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vaada"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vaada"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Mac LAN IP for dev builds; override with -PvaadaHost=<ip>. Also changeable at runtime in Settings.
        buildConfigField("String", "DEFAULT_HOST", "\"${(project.findProperty("vaadaHost") as String?) ?: "10.115.28.169"}\"")
        buildConfigField("int", "LEDGER_PORT", "8000")
        buildConfigField("int", "GATEWAY_PORT", "8100")

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":androidwakeword"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.ui.tooling)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
