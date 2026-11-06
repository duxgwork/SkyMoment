plugins { alias(libs.plugins.android.library) }

// 测试支撑层：仅被 testImplementation 引用，不进入应用运行时。
android {
    namespace = "com.example.skymoment.test"
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) }
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":Domain"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
