plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Версия задаётся здесь одним местом: отсюда её берут и манифест, и интерфейс,
// и имя файла APK. Иначе они разъезжаются, и по присланному файлу становится
// непонятно, что именно в нём собрано.
val appVersionName = "0.2.0"
val appVersionCode = 2

// Короткий хеш коммита: в CI приходит из окружения, локально его нет.
// Нужен, чтобы по установленному приложению можно было точно сказать, из какого
// коммита он собран.
val gitSha: String = (System.getenv("GITHUB_SHA") ?: "local").take(8)

android {
    namespace = "ru.translate.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.translate.overlay"
        // AudioPlaybackCaptureConfiguration появился в Android 10 (API 29) —
        // ниже приложение работать не может физически.
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        ndk {
            // Только arm64. 32-битный ARM добавлял к APK ещё 41 МБ нативных
            // библиотек, а Whisper на нём всё равно был бы неприемлемо медленным:
            // профиль «Качество» требует 6+ ГБ RAM, таких устройств на armeabi-v7a
            // не бывает.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Постоянный ключ, лежащий в репозитории.
        //
        // Иначе обновление невозможно: AGP генерирует debug-ключ на лету в
        // ~/.android/debug.keystore, а раннер GitHub каждый раз чистый — значит
        // каждая сборка подписана НОВЫМ ключом. Android отказывается ставить APK
        // поверх уже установленного приложения с другой подписью, и пользователь
        // видит только «Приложение не установлено» без объяснения причины.
        //
        // Пароль лежит рядом с ключом открытым текстом, и это осознанно: ключ
        // самоподписанный и служит только для того, чтобы подпись не менялась
        // между сборками. Приложение не публикуется в Play Store, так что
        // подменять им нечего. Для публикации понадобился бы отдельный ключ,
        // которого в репозитории быть не должно.
        create("stable") {
            storeFile = file("signing/overlay-translator.jks")
            storePassword = "overlaytranslator"
            keyAlias = "overlay"
            keyPassword = "overlaytranslator"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("stable")
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
        // Нужен для BuildConfig.VERSION_NAME и GIT_SHA в интерфейсе.
        // В AGP 8 по умолчанию выключен.
        buildConfig = true
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

// Печатает версию для CI: по ней собирается имя файла APK.
tasks.register("printVersion") {
    doLast {
        println("VERSION_NAME=$appVersionName")
        println("VERSION_CODE=$appVersionCode")
    }
}
