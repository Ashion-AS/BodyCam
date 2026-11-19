plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.bodycam"
    // media3-muxer 1.11.1 要求 compileSdk >= 36
    compileSdk = 36
    // 与已安装的 NDK 保持一致；换机器改这里即可
    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "com.dsh.bodycam"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        ndk {
            // arm64 是真机主力；armeabi-v7a 兼容老设备；x86_64 用于模拟器调试
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-ffunction-sections", "-fdata-sections")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // fMP4 封装：MediaMuxer 的 MUXER_OUTPUT_FRAGMENTED_MPEG_4 是设备私有扩展
    // （AOSP 根本没有该常量），强杀时 moov 尚未落盘，文件不可播放。
    // media3 的 FragmentedMp4Muxer 真正做 ftyp+moov 前置、moof+mdat 分片，
    // 进程被杀死后已写的分片仍可直接播放。
    implementation("androidx.media3:media3-muxer:1.11.1")
}
