// 集中配置插件仓库与 Settings 插件版本解析。
pluginManagement {
    // Settings plugins cannot use libs aliases; read their version from the same catalog.
    val catalog = providers.fileContents(layout.settingsDirectory.file("gradle/libs.versions.toml")).asText.get()
    val foojayVersion = Regex("""(?m)^foojay\s*=\s*"([^"]+)"\s*$""")
        .find(catalog)?.groupValues?.get(1)
        ?: error("Missing foojay version in gradle/libs.versions.toml")
    // 声明当前模块使用的构建插件，版本由统一目录管理。
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version foojayVersion
    }
    // 统一依赖下载来源，子模块不单独添加仓库。
    repositories {
        // 限定 Android 与 Google 插件的仓库解析范围。
        google {
            // 仅允许列出的坐标组在当前仓库解析。
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// 声明当前模块使用的构建插件，版本由统一目录管理。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}
// 强制使用统一仓库，减少模块间依赖解析差异。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // 统一依赖下载来源，子模块不单独添加仓库。
    repositories {
        mavenCentral()
        // Google 仓库仅补充 Android 坐标，通用测试依赖优先从 Maven Central 获取。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "SkyMoment"
// 壳工程、业务组件、公共业务、数据实现与基础框架。
include(":app", ":Core", ":Common", ":Datalayer", ":Domain", ":framework")
include(":feature:Home", ":feature:Search", ":feature:Weather")

// 共享测试支撑层，仅供各模块的 testImplementation 使用。
include(":Test")
