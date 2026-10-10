plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Версия и подпись релиза приходят из CI (см. .github/workflows/release.yml и docs/PUBLISHING.md).
// Локально и в debug-сборках используются значения по умолчанию.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")

// Ключ debug-подписи хранится в репозитории ТЕКСТОМ (debug-keystore.b64), а не бинарным файлом:
// шаблонный .gitignore для Android игнорирует *.keystore, из-за чего каждая сборка в CI получала
// новый случайный ключ, и Android отказывался ставить APK поверх старого.
val debugKeystoreFile: File = layout.buildDirectory.file("signing/debug.keystore").get().asFile.also { target ->
    target.parentFile.mkdirs()
    target.writeBytes(java.util.Base64.getMimeDecoder().decode(file("debug-keystore.b64").readText()))
}

android {
    namespace = "com.example.pdfscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.pdfscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 7
        versionName = System.getenv("VERSION_NAME") ?: "2.0.0"

        // Нативные библиотеки OCR только для реальных телефонов: APK заметно меньше.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Фиксированный debug-ключ: новый APK ставится поверх старого.
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.material:material:1.12.0")

    // Собственная камера (съёмка, предпросмотр, анализ кадров для поиска границ документа)
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Работа с PDF: объединение без потери качества, сборка PDF с текстовым слоем
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Распознавание текста на устройстве (модели rus и eng лежат в assets/tessdata)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
