plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.rk3588.gesturelib"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=ON")
                cppFlags += listOf("-std=c++17", "-O2")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // JVM 单元测试 (离线仿真): android.util.Log 等调用返回默认值而非抛异常
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    testImplementation("junit:junit:4.13.2")
}

// 发布坐标: com.rk3588:gesturelib:1.2.0
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.rk3588"
            artifactId = "gesturelib"
            version = "1.2.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
