// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins { alias(libs.plugins.android.library) }
// 集中配置 Android 命名空间、SDK 与编译行为。
android {
    namespace = "com.example.skymoment.domain"
    // 从版本目录读取 SDK，保证各模块一致。
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) }
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    // 统一 Java 字节码目标，并为低版本系统的 java.time 启用标准库脱糖。
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        isCoreLibraryDesugaringEnabled = true
    }
}
// 声明模块边界、运行时库和测试库，避免在源码中装配底层依赖。
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}
