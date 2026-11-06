// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

// 集中配置 Android 命名空间、SDK、构建产物和编译行为。
android {
    // 本地 Android 单元测试使用真实资源及 Robolectric 沙箱。
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "com.example.skymoment"
    // 从版本目录读取编译 SDK，保证各模块一致。
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    // 定义最低运行版本、测试入口及面向消费者的混淆配置。
    defaultConfig {
        applicationId = "com.example.skymoment"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 路由模块变化时递增版本，令 ARouter 覆盖安装时重新扫描路由表。
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()

        testInstrumentationRunner = "com.example.skymoment.SkyTestRunner"
        if (providers.gradleProperty("routeSmoke").orNull == "true") {
            testInstrumentationRunner = "com.example.skymoment.RouteSmokeInstrumentation"
        }
    }

    // 区分调试与发布产物，发布包启用代码和资源优化。
    buildTypes {
        // 配置发布包优化；本机冒烟签名只在显式启用时生效。
        release {
            if (providers.gradleProperty("localSmokeSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            // 使用默认优化规则和项目补充规则进行 R8 压缩。
            optimization {
                enable = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                if (providers.gradleProperty("routeSmoke").orNull == "true") {
                    proguardFiles("route-smoke-rules.pro")
                }
            }
        }
    }
    // 统一 Java 字节码目标，并为低版本系统启用标准库脱糖。
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        isCoreLibraryDesugaringEnabled = true
    }
    // 生成类型安全的 ViewBinding，避免手工查找控件。
    buildFeatures {
        viewBinding = true
    }
    if (providers.gradleProperty("routeSmoke").orNull == "true") {
        sourceSets.getByName("main").kotlin.directories += "src/routeSmokeApp/java"
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/routeSmoke/java"))
        sourceSets.getByName("androidTest").kotlin.directories.clear()
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    // 始终打包全部支持语言，离线语言切换无需额外下载。
    bundle { language { enableSplit = false } }
}

// 为 ARouter 处理器提供模块名，生成唯一的路由注册表。
kapt { arguments { arg("AROUTER_MODULE_NAME", "app_services") } }

// 声明模块边界、运行时库和测试库，避免在源码中装配底层依赖。
dependencies {
    implementation(libs.arouter.api)
    kapt(libs.arouter.compiler)
    implementation(project(":Domain"))
    androidTestImplementation(project(":framework"))
    androidTestImplementation(project(":Common"))
    implementation(project(":Core"))
    implementation(project(":Datalayer"))
    implementation(project(":feature:Home"))
    implementation(project(":feature:Search"))
    implementation(project(":feature:Weather"))
    implementation(libs.androidx.appcompat)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(project(":Test"))
    testImplementation(libs.robolectric)
    if (providers.gradleProperty("routeSmoke").orNull != "true") {
        androidTestImplementation(libs.androidx.espresso.core)
        androidTestImplementation(libs.androidx.espresso.contrib)
        androidTestImplementation(libs.androidx.recyclerview)
        androidTestImplementation(libs.androidx.junit)
        androidTestImplementation(libs.androidx.test.core)
        androidTestImplementation(libs.androidx.test.runner)
    }
}
