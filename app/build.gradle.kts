plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rk3588.airgesture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rk3588.airgesture"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // debug 签名打包 release, 便于 adb 安装
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // 手势识别库 (NPU 推理 + 相机 + 悬浮识别框 + 动作执行 全部封装在内)
    implementation(project(":gesturelib"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.gitee.shotter_admin:permission-library:V1.0.8.6")

}
