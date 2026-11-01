plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.protocol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.protocol"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Постоянный debug-ключ, чтобы сборки из Actions ставились поверх друг друга.
    // Путь и пароль приходят из окружения (в CI из секретов). Без них
    // используется обычный случайный debug-ключ Android Studio.
    val ksPath = System.getenv("PROTOCOL_KEYSTORE")
    val ksPassword = System.getenv("PROTOCOL_KEYSTORE_PASSWORD")
    if (ksPath != null && ksPassword != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(ksPath)
                storeType = "PKCS12"
                storePassword = ksPassword
                keyAlias = "protocol"
                keyPassword = ksPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
