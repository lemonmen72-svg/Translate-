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
            // Только arm64. 32-битный ARM добавлял к APK ещё 41 МБ нативных
            // библиотек, а Whisper на нём всё равно был бы неприемлемо медленным:
            // профиль «Качество» требует 6+ ГБ RAM, таких устройств на armeabi-v7a
            // не бывает.
            abiFilters += listOf("arm64-v8a")
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
            // x86 нужен только эмулятору, а в AAR sherpa-onnx под эту
            // архитектуру всё ещё лежит свой libonnxruntime.so, который
            // конфликтует с библиотекой из onnxruntime-android. Под arm
            // конфликта нет: там onnxruntime слинкован статически.
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    lint {
        // Сборка не должна падать из-за lint: APK нужнее, чем идеальный отчёт.
        abortOnError = false
    }
}

dependencies {
    // sherpa-onnx поставляется как AAR из GitHub Releases и кладётся в app/libs/
    // скриптом tools/fetch_sherpa.py (вызывается из CI).
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
    // Task<T> нужен явно: на нём построен мост ML Kit в корутины.
    implementation(libs.play.services.tasks)

    // Бэкенд перевода «максимальное качество»: Opus-MT в ONNX.
    implementation(libs.onnxruntime.android)
}
