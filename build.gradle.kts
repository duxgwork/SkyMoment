// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Robolectric 的 Android 沙箱依赖从标准 Maven Central 地址解析。
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    }
}
