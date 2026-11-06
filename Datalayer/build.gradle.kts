// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}
// 集中配置 Android 命名空间、SDK、构建产物和编译行为。
android {
    // 本地 Android 单元测试使用真实资源及 Robolectric 沙箱。
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "com.example.skymoment.datalayer"
    // 从版本目录读取编译 SDK，保证各模块一致。
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) }
    // 定义最低运行版本、测试入口及面向消费者的混淆配置。
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // 统一 Java 字节码目标，并为低版本系统启用标准库脱糖。
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        isCoreLibraryDesugaringEnabled = true
    }
}
// 声明模块边界、运行时库和测试库，避免在源码中装配底层依赖。
dependencies {
    implementation(libs.icu4j)
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    implementation(project(":Domain"))
    implementation(libs.okhttp)
    implementation(libs.retrofit.serialization)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(project(":Test"))
    testImplementation(libs.robolectric)
}
