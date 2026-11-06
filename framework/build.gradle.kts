// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins { alias(libs.plugins.android.library) }
// 集中配置 Android 命名空间、SDK、构建产物和编译行为。
android {
    // 本地 Android 单元测试使用真实资源及 Robolectric 沙箱。
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "com.example.skymoment.framework"
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
    // 生成类型安全的 ViewBinding，避免手工查找控件。
    buildFeatures { viewBinding = true }
}
// 声明模块边界、运行时库和测试库，避免在源码中装配底层依赖。
dependencies {
    api(libs.androidx.fragment)
    api(libs.androidx.lifecycle.viewmodel.ktx)
    api(libs.kotlinx.coroutines.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(project(":Test"))
    testImplementation(libs.robolectric)
}
