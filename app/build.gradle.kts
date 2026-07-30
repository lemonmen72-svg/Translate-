plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.translate.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.translate.overlay"
        // AudioPlaybackCaptureConfiguration появился в Android 10 (API 29) —
        // ниже приложение работать не может физически.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Только ARM: x86 нужен лишь для эмулятора, а нативные библиотеки
            // sherpa-onnx весят немало.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Подписываем debug-ключом: приложение не публикуется, но
            // release-сборка должна устанавливаться без ручной подписи.
            signingConfig = signingConfigs.getByName("debug")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        // Сборка не должна падать из-за lint: APK нужнее, чем идеальный отчёт.
        abortOnError = false
    }
}

dependencies {
    // sherpa-onnx поставляется как AAR из GitHub Releases и кладётся в app/libs/
    // скриптом tools/fetch-sherpa.sh (вызывается из CI).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // Бэкенд перевода по умолчанию: быстрый, офлайн после первой загрузки моделей.
    implementation(libs.mlkit.translate)

    // Бэкенд перевода «максимальное качество»: Opus-MT в ONNX.
    implementation(libs.onnxruntime.android)
}
