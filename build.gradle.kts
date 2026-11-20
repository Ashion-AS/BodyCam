// 顶层构建脚本：只声明插件版本，具体配置在 app/build.gradle.kts
plugins {
    id("com.android.application") version "8.10.0" apply false
    // media3 1.11.1（exoplayer / ui）是 Kotlin 2.2 编译的构件，
    // 用 2.0.21 编译器读它的元数据会报 "incompatible version of Kotlin"，
    // 因此编译器跟着抬到同一代。
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}