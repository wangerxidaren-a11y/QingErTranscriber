plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.erxi.qingertranscriber"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.erxi.qingertranscriber"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.4"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 固定发布签名：CI 从 Secrets 注入 keystore 与密码，保证每次构建签名一致，
    // 以后可直接覆盖升级。本地 release 构建需自行设置 KEYSTORE_PATH 等环境变量。
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v1.3.5：WorkManager（每天 21:00 自动总结 + 录音看门狗，无需精确闹钟权限）
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
