plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mizhii.ledger"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.mizhii.ledger"
        minSdk = 34
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }

    /**
     * 自用侧载的签名方式。
     *
     * 刻意复用 Android 调试证书（`~/.android/debug.keystore`，口令是公开的 `android`）：
     *  - 好处：与 debug 包**同一个签名**，可以用 `adb install -r` 直接覆盖升级，不会清数据；
     *    换成新 keystore 就必须先卸载，而卸载会连同 Room 里的账本一起删掉；
     *  - 代价：不具备"签名即身份"的意义，**不要**用它上架应用商店；
     *  - 仓库里不放任何密钥文件，这个证书由构建机自己生成。
     */
    signingConfigs {
        create("selfSigned") {
            // 必须用与 debug 构建**同一个** keystore，否则覆盖安装会因签名不一致失败。
            // AGP 解析 debug keystore 的顺序：ANDROID_USER_HOME → ANDROID_SDK_HOME → ~/.android
            val candidates = listOfNotNull(
                System.getenv("ANDROID_USER_HOME")?.let { File(it, "debug.keystore") },
                System.getenv("ANDROID_SDK_HOME")?.let { File(it, ".android/debug.keystore") },
                File(System.getProperty("user.home"), ".android/debug.keystore"),
            )
            val store = candidates.firstOrNull { it.exists() }
            if (store != null) {
                storeFile = store
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                logger.warn("找不到 debug.keystore，release APK 将不会签名（仅用于本地检查）")
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
            signingConfig = signingConfigs.getByName("selfSigned")
            // 只在小米 14（arm64）上验证过，其他 ABI 明确不支持（见 README 兼容性声明）
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    // Room 导出 schema，供后续版本迁移使用（禁止仅依赖 destructive migration）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Room：本地存储（§8）
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // DataStore：设置项（§8.8）
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    // 单元测试（§12 各里程碑验收要求）
    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
