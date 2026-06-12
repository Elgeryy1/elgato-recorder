plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gery.elgatorecorder"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gery.elgatorecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Solo incluir arm64-v8a (S25 Plus). Evita .so duplicados de otras ABIs.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // AUSBC distribuye .so precompilados; permitir duplicados si ocurren
            pickFirsts += setOf("**/libjpeg-turbo1500.so", "**/libusb100.so", "**/libuvc.so", "**/libUVCCamera.so")
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    // AUSBC: acceso UVC directo por USB, sin Camera HAL, sin NDK propio
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        // Excluir dependencias UI de AUSBC que no están en Maven Central ni JitPack estándar
        exclude(group = "com.gyf.immersionbar")   // status-bar utility — no necesario
        exclude(group = "com.zlc.glide")           // webp decoder  — no necesario
    }
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}