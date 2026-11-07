# 工程基础验证

## 启动天气失败与模拟器 DNS 修复（2026-11-07）

- 用户截图为天气请求失败空态。API 35 模拟器已授予前台位置权限，定位服务日志确认向应用交付坐标；同时域名探测返回 `unknown host`，直接 IP 可达，主机请求天气接口返回 HTTP 200。故障位于模拟器 DNS 链路。
- 使用主机当前可用的 IPv4 DNS 显式启动原 AVD 并冷启动，保留原应用数据和权限。恢复后模拟器能够解析天气服务域名；生产应用使用系统坐标及真实 Open-Meteo 响应，自动显示当前天气、24 小时和七日预报，强制停止后再次启动也成功。模拟器坐标是已有的模拟位置，不代表真实设备定位覆盖。
- HomeUiState 保留网络、超时、限流等天气失败原因，首页复用三语错误文案；合法空响应仍使用空数据文案。网络错误或超时后，恢复连接并回到前台会重新启动定位和天气请求；不会重复启动在途请求、替换已就绪天气，或绕过限流／配额的显式重试。
- 设备回归确认：关闭移动数据后显示“请检查网络后重试。”；返回桌面、恢复网络并确认域名可达后，再进入同一首页可自动恢复当前天气和预报，无需点击重试。若在网络尚未恢复时就回到首页，仍可能请求失败，需要连接恢复后重试或重新进入前台。
- `./gradlew testDebugUnitTest :app:assembleDebug :feature:Home:lintDebug --console=plain` 成功。106 项单元测试，0 失败、0 错误、0 跳过；新增覆盖错误分类保留、超时原因、返回前台恢复和非网络失败不自动重试。

如果重启模拟器或切换主机网络后复现域名解析失败，关闭该 AVD 后按 [Android 官方 DNS 配置说明](https://developer.android.com/studio/run/emulator-networking-dns)，使用当前网络可用的 DNS 启动（替换下面的占位值）：

```bash
"$ANDROID_HOME/emulator/emulator" -avd Pixel_8_API_35 \
  -dns-server <当前网络可用的IPv4_DNS> \
  -no-snapshot-load -netdelay none -netspeed full
```

`-dns-server` 是模拟器启动参数，需要在之后的模拟器启动命令中保留；本次没有修改主机全局 DNS，也没有在应用内硬编码 DNS、IP、代理或天气数据。

## 全组件本地单元测试（2026-11-06）

- 新增 Test Android Library，位于生产分层之外，仅供 testImplementation 使用；共享主线程规则、虚拟时钟、领域样本与会话替身。Home 单元测试不再依赖 Datalayer。
- 恢复业务回归测试，并补充 Domain 模型约束、历史持久化、路由契约／服务发现、三语资源、基础 Binding／订阅生命周期、系统定位和逆地理编码等本地测试。Android 行为使用 Robolectric API 35 沙箱，不需要设备。
- `./gradlew testDebugUnitTest :app:assembleDebug --console=plain` 成功。JUnit XML 确认 104 项通过，0 失败、0 错误、0 跳过：Domain 7、Test 3、Datalayer 30、Core 5、Common 4、framework 15、Home 14、Search 6、Weather 13、app 7。
- `:app:dependencies --configuration releaseRuntimeClasspath` 确认发布运行时不含 Test、JUnit、kotlinx-coroutines-test 或 Robolectric。`git diff --check` 和暂存区差异检查通过。
- 首次解析依赖时 Google Maven 直连超时，使用本机现有代理下载；最终全量测试与 Debug 构建使用常规命令成功。项目不记录本机代理配置。
- 本次验证不包含 Release 单元测试、设备仪器测试或 100% 行覆盖率承诺；测试边界与复用方式见 [测试指南](testing.md)。

## Android Studio 模块同步修复（2026-11-06）

- 用户反馈 IDE 无法编译，Build / Sync 面板报 `Can't find module entity for SkyMoment.core`。日志定位到 `GradleModuleModelDataService.importData`，发生在 Gradle 已成功返回模型之后。
- 命令行 `./gradlew build --console=plain` 成功（741 个任务），确认失败点为 IDE 导入旧模块模型。旧缓存仍保存 `SkyMoment.core`、`core.domain`、`core.ui` 等已迁移模块；只修改 `.idea/gradle.xml` 并重试同步不足以更新这些缓存。
- 关闭 SkyMoment 项目，备份 `.idea` 设置以及 Android Studio 当前版本下该项目的 `external_build_system`、`project-model-cache` 缓存，重新打开项目并执行 Gradle Sync。仅重建 SkyMoment 的模块模型，保留运行配置和其他项目状态。
- 修复后 Android Studio 日志记录 `onImportFinished`、`Gradle sync finished in 2 s 287 ms`；在 IDE 内执行 `Assemble 'app' Run Configuration`，输出 `BUILD SUCCESSFUL in 1s`（189 个任务）。架构边界检查和 `git diff --check` 通过。
- 后续组件改名验收必须同时包含命令行构建和 Android Studio 同步／构建，避免遗漏 IDE 的项目模型缓存问题。

## OSL 组件架构重构（2026-11-06）

- 参考 OSL Global Android 和其 `osl-android-design/framework` 基类源码；完成 Core、Common、Datalayer、Domain、framework 与 feature/Home、Search、Weather 的模块及包名迁移。首页移出 app，跨业务组件通过 Core 服务契约通信。
- BaseActivity / BaseFragment 改为单 ViewBinding 泛型及显式绑定工厂，页面自行声明 ViewModel；保留 savedInstanceState、视图生命周期订阅、绑定清理、语言上下文及系统栏处理，删除泛型反射与相关 R8 保留规则。
- `python3 scripts/check_architecture.py` 通过：9 个模块、22 条生产依赖，无循环、跨 feature 引用或跨模块重复 ARouter group。
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :Domain:test` 通过。共 77 项单元测试：Domain 5、Datalayer 26、framework 11、Common 2、Home 14、Search 6、Weather 13。
- API 35 模拟器 `emulator-5554` 安装 Debug 应用及测试 APK，执行 `am instrument -w com.example.skymoment.test/com.example.skymoment.SkyTestRunner`：20 项通过。包含 Fragment detach/attach、订阅取消重启、SavedStateHandle 参数、ViewModel 保留与最终清理，以及首页恢复、搜索历史、天气刷新和沉浸式边距。
- `./gradlew lint :app:assembleRelease` 通过；app lint 报告为 0 errors、18 warnings，包含依赖更新提示和已有备份配置提示。
- `./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest -ProuteSmoke=true -PtestBuildType=release -PlocalSmokeSigning=true` 通过。相同模拟器执行 `RouteSmokeInstrumentation`：1 项通过，验证 R8 后首页／搜索真实路由、天气 Fragment 服务创建、XML 和 ICU 拼音资源。
- 整理后再次执行普通 `:app:assembleDebug` 通过；模拟器恢复普通 Debug 安装。以上验证使用现有合成数据与 API 35 环境，不扩大 API 24／目标 API 真机和真实区域网络的验收范围。
- 当前架构见 [工程架构](architecture.md)；旧 draw.io／PNG／SVG 架构图保留为重构前历史资料。

## 启动自动定位与失败回退（2026-11-05）

最新启动流程覆盖下文历史记录中的“不自动定位／首次空状态”：先申请前台位置并加载当前位置天气，拒权、定位失败／15 秒超时、当前天气失败／为空／30 秒超时才回退搜索引导。成功的当前天气随会话保存在内存，天气页面复用后单独加载预报；手动选择取消自动流程。缺失值保留统一的 `WeatherPresentation.MISSING_VALUE = "--"`。

- 47 项相关单元测试通过（app 7、domain 2、ui 13、data 15、search 3、weather 7）。新增覆盖拒权、定位空／超时、天气失败／空／超时、迟到结果不覆盖手动选择、退后台取消定位、内存恢复、当前位置 `timezone=auto` 及首屏不重复查询当前天气。
- API 35 模拟器的 12 项仪器测试全部通过，包括自动天气成功并旋转、预报失败保留当前天气、自动天气失败回退引导、原有手动搜索流程、Fragment 生命周期和其他页面回归。自动分支使用 Fake 坐标与天气，不能代替真实设备定位覆盖。
- Debug／Release 构建及全工程 lint 通过。首次构建曾遇到 ARouter 生成文件缺失，清理 app 与两个 feature 的构建产物后通过，未修改路由处理器或关闭增量检查。
- 另在模拟器生产启动中观察到系统前台定位授权弹窗及“当前位置”的真实天气显示；模拟位置不代表用户实际位置，也不计入区域网络／真机定位验收。

定位权限与系统服务在 API 24／目标 API 真机上的完整矩阵仍需对应设备验证。

后续地点名称要求已接入系统逆地理编码：天气展示后独立解析区县／城市及行政路径，4 秒超时、无结果或异常均保持当前名称和天气，不回退占位、不增加错误提示。数据模块 16 项、天气 ViewModel 8 项单元测试通过；首页 4 项 API 35 仪器测试通过，覆盖解析名称展示、未解析时保留当前位置标题、自动天气失败和手动搜索。最终 Debug／Release 构建与 app lint 通过。名称单元／页面测试使用替身，不宣称系统 Geocoder 在所有地区均可用。

验证日期：2026-11-05。此记录只证明工程构建，不代表真实天气服务验收通过。

## 工具链

- Gradle 9.5.0、AGP 9.3.3，Gradle daemon 使用 JDK 21；启动器为本机 Azul JDK 17。
- 源码目标 Java 11；minSdk 24、targetSdk 36。
- 用户确认 compileSdk 37：core-ktx 1.19.0 的 AAR 元数据要求 minCompileSdk 37，本机已有 `platforms;android-37.0`。
- 页面采用 XML + ViewBinding。

## 已执行

| 验证 | 结果 |
| --- | --- |
| `./gradlew --version` | 通过，版本如上 |
| `./gradlew help` | 通过 |
| `./gradlew help :app:assembleDebug --console=plain` | 通过，35 个任务执行，配置缓存已存储 |

本机用户级 Gradle 配置含空的代理端口，SDK/依赖下载曾因此失败。上述构建在命令末尾附加 `-Dhttp.proxyHost= -Dhttp.proxyPort=80 -Dhttps.proxyHost= -Dhttps.proxyPort=443` 覆盖无效端口；未修改用户全局配置。这是本机环境处理，不是项目必需的代理设置。

后续模块、路由和设备验证将在完成后追加，不能把本节单模块构建结果当作最终验收。

## 八模块基础

`./gradlew projects :core:domain:build assembleDebug` 通过，205 个任务执行。`projects` 确认八个可构建模块（另有 core/data/feature 目录分组）；七个 Android namespace 唯一，domain 为纯 Kotlin/JVM。检查所有 `implementation(project(...))`：feature 仅依赖 core，core 无上层依赖。所有 Android 模块开启 Java 11 与 core library desugaring 2.1.5；UI 模块开启 ViewBinding，统一依赖版本见版本目录。

## 依赖注入与会话

两项 ViewModel Fake 单元测试通过：搜索按指定语言调用替代仓储、不自动选中结果；天气两个区域独立完成、初始化重复调用合并、失效 token 不发请求。内存会话测试通过，验证新选择、清空和新实例使旧 token 失效。feature 只导入 domain 接口，无 data DTO 或 Retrofit 引用。应用通过 AppContainer 显式组装，当前仓储明确返回配置不可用，尚未实现真实 API。

## XML 状态与语言基础

LocaleResolver 的 11 组参数化单元测试通过，覆盖脚本优先、地区、首选语言和英文回退。API 35 模拟器上的 debug 仪器测试 4 项通过（其中 3 项为本次基础验证，另 1 项为原有包名检查）：

- StatusPanel 加载／空／错误状态可区分，重试按钮回调可点击。
- 两个生成路由可打开，天气 token 失效返回搜索。
- 在 LocaleContext 的受控语言上下文中实际加载 XML，验证英文、简体、繁体和法语回退英文文案；这不代替后续系统设置变化与在途请求切换验收。

`./gradlew lint :app:dependencies --configuration debugRuntimeClasspath` 通过；依赖报告逐项匹配 architecture.md 的八模块依赖表。lint 修复了 minSdk 24 下不适用的导航栏样式属性，并由兼容接口处理；app 固定打包三语资源，避免语言拆分影响离线语言回退。

## ARouter 发布包设备验证

`./gradlew :app:connectedReleaseAndroidTest -PtestBuildType=release -PlocalSmokeSigning=true -ProuteSmoke=true` 已在 Pixel 8 API 35 模拟器通过 1 项平台 Instrumentation 冒烟：Launcher 经 ARouter 打开搜索，合成会话 token 经 ARouter 打开天气并检查地点与 XML 状态组件，再返回原搜索页。R8 压缩开启，使用本机 debug 签名，配置缓存复用成功。

排查并解决两项实际工具链问题：

- ARouter 1.5.2 的 support 依赖与 AndroidX 冲突，通过 Jetifier 迁移；当前 AGP 发出该开关未来在 10.0 移除的提示，未变更已确认的 AGP 9.3.3。
- R8 full mode 裁剪内建 IProvider 的反射默认构造器，引发初始化失败；consumer 规则明确保留对应构造器，设备复测通过。

常规 AndroidJUnitRunner 在独立压缩测试与目标应用共享 Kotlin/Tracing 类时启动失败，因此 release 使用只依赖平台的 RouteSmokeInstrumentation，并仅保留测试注入所需的公开会话接口与 Location 构造器。完整 Espresso 场景在 debug 运行；该选择未关闭应用混淆，也未增加 ARouter 保留范围来绕过路由检查。

连续两次执行同一命令 `./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` 均通过；第二次明确输出 `Configuration cache entry reused`。检查 debug/release 的 ViewBinding 类及 `ARouter$$Root$$feature_search` / `ARouter$$Root$$feature_weather` 生成表均存在。

## 页面预览最终检查

最终 debug 的 FoundationUiTest 三项均通过。状态组件在独立挂载的 View 中测试；失效 token 测试等待实际回退 Intent，避免 ARouter 异步导航泄漏到下一用例。未使用固定 sleep。已检查实际 XML 渲染截图，修正默认深色文字为白色，确认没有指定删除的三段切换控件。后续 `:app:assembleRelease lint` 通过，lint 为 0 错误；保留已确认 targetSdk／依赖版本带来的更新提示不代表构建失败。

截图位于 `docs/screenshots/`：天气页为合成地点和无数据状态；搜索三语截图使用受控语言 Context，不冒充真实天气或完整系统语言切换验收。

第 2 组基础任务已全部完成。用户随后确认改用 Open-Meteo，后续开发说明与契约以新的供应商规划为准；本记录中此前的“配置不可用”仅描述基础阶段，不是新供应商要求。


## Open-Meteo 与完整 XML 流程（2026-11-05）

供应商迁移后的结果覆盖工程基础以后的实现，前面的占位页面记录仅作为历史工程验证。当前 AppContainer 接入真实公共 API，不再依赖天气配置。

- 46 个唯一 JVM 单元测试全部通过（app 1、domain 2、network 7、ui 13、data 14、search 3、weather 6）。HTTP 测试使用本地 HTTPS MockWebServer，包括压缩、空／错误 envelope、命名坐标、无鉴权、超时、取消、429、坏 JSON 与 Retry-After 溢出；行为测试包含代次、午夜、旧数据与冷却。
- API 35 Pixel_8_API_35 上 WeatherFlowTest 4 + FoundationUiTest 3 全部通过。覆盖显式候选选择、两次切换、返回、无效 token、输入回写不重复查询、旋转／后台恢复、零值与缺失、刷新失败原时间、缺今天与 6/7 行、ICU 实际转换和通用状态组件。
- 使用 `-e uiLocale zh-Hans / zh-Hant / fr-FR` 额外各通过 1 项真实 WeatherActivity 场景；参数仅覆盖测试进程 Resources 的系统语言快照，不修改设备全局设置。三语和英文回退截图在 `screenshots/weather-evidence/`，Synthetic District／Province A 及天气数值为合成数据，不能当作 API 实测。所有截图已视觉检查。
- 生产组装在设备上实际查询 London，普通日期窗口和来源链接可见；截图 `live-london*.png` 为真实 API 响应，网络地域仍未知。
- 无天气凭据 debug／release 构建通过，lint 0 错误；release R8 开启。压缩包平台冒烟 1 项通过，覆盖 Launcher→Search→Weather→返回和 ICU 拼音资源。为避免测试 APK 自己裁剪的 java.time 遮蔽应用类，release 使用独立纯 Java 源集与应用内测试桥；普通构建不含此桥。
- Android 平台 Transliterator 在 API 29 才开放，lint 发现后改为 ICU4J 77.1，保留 minSdk 24。补测发现通用 ICU 将新都转为 xindou，采用地名“都”的 dū 读音规则，4 个代表中文名称的真实 ICU 转换测试通过。没有加入城市数据库。
- 尝试安装 API 24／36 系统镜像，SDK manager 返回 `Error on ZipFile unknown archive`，安装命令退出 1；没有生成可用的新 AVD。两档设备矩阵仍未完成，不能将 API 35 或 lint 结果替代最小／目标 API 实测。

最终验证命令仍需追加本机的空代理端口修正参数（见 README）。debug 的测试 runner 为 `com.example.skymoment.SkyTestRunner`；release 冒烟 runner 为 `com.example.skymoment.RouteSmokeInstrumentation`。

外部门槛：真实当地午夜前后连续覆盖、大陆宽带／移动／海外各十次 App 流程和延迟，以及 API 24／36 完整矩阵尚未完成。OpenSpec 保留相应未勾选任务，不宣布整体验收通过。页面场景对应见 [页面验证映射](page-validation.md)。

最终普通构建（不含 routeSmoke 测试桥）的 `:app:assembleDebug :app:assembleRelease lint` 通过。另在压缩后的本机签名 release 中实际搜索 London 并读取真实数值与今日高低温成功，确认 Retrofit／序列化链路可用；查询发生于 London 当地 04:10，未计入区域性能样本。OpenSpec 当前 31/36，未完成项为 1.3、1.4、7.2、7.3、7.4。


## 同屏天气首页（2026-11-05）

本轮验证时入口为 Launcher → `/app/home`（后续简化见下一节）。HomeActivity 组合搜索／天气两个 Fragment，首次无选择不请求天气，选择后原地替换天气内容，旋转保留 ViewModel。浅灰背景、白色卡片、左对齐大温度和紧凑七日列表取代之前的蓝色天气页；移除所选地点标签、全页刷新按钮、预报获取时间和天气来源页脚，保留当前获取时间及局部重试。

本轮验证：

- debug 和普通 unsigned release 构建成功，lint 0 错误。新增 `ARouter$$Root$$app_home` 在 debug/release 均生成。最终普通构建不含 routeSmoke 桥接类。
- API 35 的 HomeFlowTest 1、WeatherFlowTest 4、FoundationUiTest 3 共 8 项通过。同屏用例验证首次空态、两次选择、清空输入、旋转不重复请求及 Fragment 返回栈为 0。旧独立路由测试增加恢复到搜索页的状态等待，避免把 ARouter 异步导航当成同步操作。
- 同屏流程额外在 zh-Hans-TW、zh-Hant-CN、fr-FR 各通过一次；连同默认英文，检查简繁和英文回退截图。测试只覆盖进程资源快照，不声称完成所有系统设置变化矩阵。
- 删除全页刷新后补齐不完整预报的区域重试；设备用例确认六天可恢复到七天且不额外请求当前天气。译名失败在再次前台时重试，受影响的 WeatherViewModel 6 项单元测试全部通过。其他未变化的单元测试沿用上节 46 项唯一测试结果。
- 保留旧安装数据时，首次 release 冒烟发现 ARouter 缓存没有新增首页。确认其缓存只比较版本号后，将 versionCode 从 1 升至 2；没有清空应用数据。覆盖安装复测通过 1 项压缩包平台冒烟，Launcher → Home → 独立 Weather → Home 正常，并验证 ICU 拼音资源。
- 生产仓储在新版首页实际完成 London 搜索及同屏天气展示：当地 08:51 获取，今日预报高低温与首行一致，七天为 11 月 5 日至 11 日。强制停止后重新启动，恢复未选择引导，不恢复旧地点天气。网络地域未知，不计入地区性能验收。
- 截图位于 `screenshots/home-evidence/`：`home-live-loading.png`、`home-live-london.png`、`home-live-forecast.png` 来自真实 API；其余为合成测试数据。已检查实际渲染及列表末尾。

OpenSpec 严格校验通过；本轮首页任务完成后为 34/39。仍待真实午夜连续请求、三种区域网络、API 24／36 设备矩阵和最终汇总验收（1.3、1.4、7.2、7.3、7.4）。没有将本轮 API 35、模拟仓储或本机网络结果代替这些门槛。

## 启动入口简化（2026-11-05）

按用户要求删除启动中转 Activity，HomeActivity 直接声明 MAIN / LAUNCHER 和 exported=true；保留 /app/home 路由及 Application 中的 ARouter 初始化。

- `./gradlew :app:assembleDebug :app:lintDebug --console=plain`（附加上述本机代理修正参数）通过。
- 在已连接的 emulator-5554 覆盖安装 debug APK 后，包管理器将 MAIN / LAUNCHER 解析为 `com.example.skymoment/.HomeActivity`。使用该组件和 MAIN / LAUNCHER Intent 冷启动成功，`am start -W` 返回 `Status: ok`，任务栈仅有 HomeActivity。
- 架构、设计和任务清单已同步。新增 8.4 完成后进度为 35/40，原有五项验收门槛继续保留未验证。


## 2026-11-05：24 小时预报

- 在七日预报上方加入横向小时卡片，显示地点当前小时至未来 23 小时；整点更新、跨天日期、昼夜图标、缺失占位和独立重试已实现。
- `:core:domain:test`、`:core:ui:testDebugUnitTest`、`:data:weather:testDebugUnitTest`、`:feature:weather:testDebugUnitTest`、`:app:testDebugUnitTest` 共 53 项通过，覆盖跨天／DST／非整点时区、数组错位、缺失字段、整点刷新失败保留数据和共享冷却。
- Debug 应用与测试 APK 构建通过；app 与 feature:weather 的 Debug lint 通过，无新增警告。
- emulator-5554 简体中文 `WeatherFlowTest` 5 项通过；最终排版调整后小时预报专项测试再次通过。验证卡片顺序、24 项数据、横向滑动、末小时可见和旋转不重复请求，并检查截图排版。
- 真实 Open-Meteo 接口对 Asia/Shanghai 与 Asia/Kathmandu 返回 24 个连续小时，温度单位 °C，时间戳为 unixtime；这两次请求不代替区域网络与连续跨时验收。

## 2026-11-05：代码精简与复用

- 天气三个区域共用请求管理，保留独立重试、失败保留数据、共享冷却与旧响应代次校验；新增七日／小时预报交错完成及旧语言响应晚返回的回归测试。
- 搜索入口共用状态渲染，搜索与天气共用错误文案映射；手写 ViewModel 工厂改为 AndroidX 初始化器。删除示例测试、空备份规则模板及四组无引用文案，保持关闭备份。核心状态、数据映射和时间窗口补充中文注释。
- `./gradlew testDebugUnitTest :core:domain:test :app:assembleDebug :app:assembleDebugAndroidTest lintDebug :app:assembleRelease` 通过。63 项唯一 JVM 单元测试全部通过（app 6、domain 5、network 7、ui 13、data 18、search 3、weather 11）；lint 0 错误，仍有依赖版本／目标 API、过度绘制和小字号等既有警告。Release 包未签名，R8 开启。
- `./gradlew :app:connectedDebugAndroidTest` 在 API 35 的 emulator-5554 上通过全部 13 项：BaseFragmentLifecycleTest 1、FoundationUiTest 3、HomeFlowTest 4、WeatherFlowTest 5。覆盖首页定位及失败回退、地点选择、路由、旋转／后台恢复、三语资源、小时横滑与区域重试。
- 上述 Gradle 命令附加 README 中的本机代理修正参数。本轮不扩大 API 24／36、真实区域网络或连续午夜的既有验收结论。

## 2026-11-05：首页右上角切换地点

- 首页搜索框替换为右上角“切换”按钮，通过 `LocationSearchContract` 打开独立搜索页；选择后返回原首页更新天气，取消保留原地点。移除不再使用的嵌入搜索 Fragment、布局和宿主回调。
- 进入搜索时暂停尚未结束的自动定位／天气请求，防止旧响应在搜索结果回传前替换手动选择的内存会话；补充该竞争场景的单元测试。
- app 与 feature:search 共 10 项相关单元测试通过；Debug 应用／测试包、Release 构建及相关 Debug lint 通过，lint 无错误。
- API 35 模拟器全部 14 项 UI 测试通过，包括切换入口、两次选择返回同一首页、取消保留原天气、旋转与原有独立搜索路由。已安装 Debug 包并检查实际中文画面，“切换”位于首页右上角。

## 2026-11-05：独立搜索页恢复浅色样式

- 将原首页搜索区域的布局用于独立搜索页：浅灰背景、白色圆角搜索框、搜索／清空图标及白色地点卡片；列表扩展到页面剩余空间，空闲状态仅显示输入框。移除深色搜索主题、渐变背景、品牌标题、副标题和大搜索按钮。
- 保留防抖查询、键盘搜索、错误重试、选择回传和取消返回；既有 UI 测试改为通过输入框识别搜索页，三语检查改为输入提示，增加搜索页不显示天气温度视图的断言。
- Debug 应用与测试包、Release 构建及 app／feature:search 的 Debug lint 全部通过；API 35 模拟器 14 项 UI 测试通过。已从首页点击右上角“切换”，实际检查中文搜索页的浅色背景、搜索框与无天气信息的空态。


## 2026-11-05：历史搜索标签

- 搜索框下新增历史标签及清空操作；仅记录主动提交或选择地点的关键词，本机最多保存 10 条，去空白、忽略大小写去重、最近使用置顶。标签点击回填后只触发一次查询，输入防抖不写历史。
- 搜索 ViewModel 4 项单元测试通过；API 35 模拟器全部 16 项 UI 测试通过，新增覆盖持久化、条数上限、去重、重新打开页面、标签点击、旋转及清空。Debug／Release 构建和 app／feature:search lint 通过。

## 2026-11-05：启动空态与搜索返回按钮

- 首页无位置或天气时使用共享 WeatherEmptyView，替换整张 `--` 占位天气卡片；区分定位未授权、定位失败和天气不可用，提供搜索地点及设置／重试。手动选择地点后，所有区域均无有效天气且请求结束时也展示空态，部分成功时仍保留天气内容。
- 搜索页左上角新增返回箭头，沿用系统返回行为；返回首页保留既有天气或空态，浅色样式及历史标签保持可用。新增文案覆盖英文、简体和繁体，标题无障碍语义使用兼容接口。
- 首页 ViewModel 8 项、天气 ViewModel 11 项单元测试通过。API 35 模拟器全部 17 项 UI 测试通过，覆盖空态搜索与返回、启动重试、手动天气全空后恢复、已有天气保留及历史回归。最后的无障碍兼容调整和冗余分支清理后，Debug／Release 构建、app／core:ui lint 再次通过，core:ui 无 lint 问题。
- 已安装 Debug 包检查中文实际画面：`screenshots/home-evidence/home-permission-empty.png` 为拒绝定位权限后的空态；`screenshots/home-evidence/search-history-back.png` 为实际提交 London 关键词后的搜索历史与返回按钮。截图不作为天气接口或真实定位的地区可用性验收。


## 2026-11-05：沉浸式布局与启动加载界面

- 首页、搜索页以及共用基类的天气页统一启用透明系统栏，背景铺满屏幕；根布局统一处理系统栏、刘海和 IME 边距，搜索入口启用 adjustResize。Android 7 导航图标不支持深色，底部保留深色背景保证对比度。
- 在 LocalizedActivity.onCreate 恢复 Decor 之前关闭系统自动避让，修正 API 35 复用 Window 后系统再次消费边距的问题。测试实际检查背景从屏幕顶部开始、窗口高度、系统栏透明、顶部操作安全区域及重建前后边距一致。
- 启动加载改为全页 WeatherLoadingView：天气矢量插画、阶段标题与说明、细线不定进度、定位／天气步骤。定位成功后显示已确认，按真实请求更新；旋转不重复定位／请求，加载完成后切换天气，失败沿用已有空态。英文、简体和繁体文案同步补齐。
- Debug 应用和仪器测试包、Release 构建、app／core:ui Debug lint 通过；core:ui 零 lint 问题，app 零错误。API 35 模拟器运行 SkyTestRunner 全套 19 项设备测试通过（adb am instrument），包括新增的两项 ImmersiveStartupTest，覆盖真实加载阶段、重建、键盘边距及现有页面回归。git diff --check 通过。
- 已视觉检查 `screenshots/immersive-evidence/startup-location.png`、`startup-weather.png` 与 `search-immersive.png`。截图使用可控测试响应，验证界面和状态转换，不代表真实定位或网络可用性。

## 2026-11-05：页面精简与应用依赖单例

- 删除 WeatherActivity、`/weather/detail` 和天气模块的 ARouter 处理器，天气统一由 HomeActivity 中的 WeatherFragment 展示。布局更名为 `fragment_weather.xml`，移除仅用于旧页面的切换按钮与外边距。普通搜索路由选中后回到首页，已有首页通过 onNewIntent 更新；失效会话清理旧天气 Fragment。
- Routes 只保留页面地址；SESSION_TOKEN 移至 NavigationExtras。versionCode 从 2 升至 3，令覆盖安装更新已删除模块的路由扫描缓存。Debug APK 检查确认不再包含 WeatherActivity、旧天气地址或 feature_weather 路由根表。
- SkyMomentApplication 只在 onCreate 初始化 ARouterNavigator。新增 AppServices，由 ARouter 首次访问时创建并缓存为进程单例，使用 Application Context 懒加载定位与仓储。页面通过 ApplicationServices 契约读取依赖，删除三个 Application Owner 接口及相关强转；DeviceLocationProvider 契约移至纯 Kotlin domain。ServiceRoutes 单独维护服务地址。测试通过动态服务路由提供 Fake，验证单例复用。
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :feature:weather:testDebugUnitTest :core:domain:test :app:lintDebug :app:assembleRelease` 通过，24 项相关唯一单元测试通过（app 8、weather 11、domain 5）。普通 Release 保持 R8 开启，lint 无错误。
- API 35 模拟器全套 19 项 Debug UI 测试通过，覆盖路由复用、会话失效、独立搜索返回首页、搜索历史、加载与空态、沉浸式布局及旋转恢复。测试初次迁移遇到重复空态视图匹配与 ActivityScenario Intent 跟踪问题，调整测试定位和启动 Intent 后全部通过。
- `:app:connectedReleaseAndroidTest -PtestBuildType=release -PlocalSmokeSigning=true -ProuteSmoke=true` 通过 1 项压缩包冒烟：真实 Application 初始化、服务单例解析与会话共享、首页 → 搜索路由 → 首页、天气 Fragment XML 和 ICU 拼音均正常。UTP 未采集到该项 logcat，仪器测试与 Gradle 均报告成功。命令使用 README 中的本机代理修正参数。

## 2026-11-05：单向分层、冗余清理与设计说明

- 删除 navigation 中的 ApplicationServices 混合业务契约，导航层改为通用 ServiceLocator；AppDependencies 与定位契约留在 app，feature 只读取领域 WeatherDependencies。基础 ui、navigation、network 均无项目内模块依赖。
- 新增 shared:weather-ui，承接天气错误映射、WMO 展示、格式化、空态／加载组件和三语业务资源。数据层工厂封装 Open-Meteo 服务与共享网络资源；供应商端点及配额 reason 解析从 network 移至 data，删除 app → network 直接依赖。
- 清理无消费者的 Navigator 接口、ServiceFactory 转发封装、旧深色主题、标题样式、两个颜色、两组三语未引用文案和 ConstraintLayout 依赖。迁移时保留原布局及交互。
- 架构图、模块表和运行时装配说明已合并至 `docs/architecture.md`，删除独立 layered-architecture 文件并更新链接；补充架构／组件、状态管理、API 集成、代码组织、错误处理与可维护性六项设计说明。
- `python3 scripts/check_architecture.py` 通过：9 个模块、19 条生产依赖均严格向下、无环；图中依赖与 Gradle 声明逐条一致。临时注入非法源码引用、逆向 Gradle 依赖及循环的负向检查均被拒绝，临时文件已清理。
- `:app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :core:domain:test lintDebug :app:assembleRelease` 通过。67 项当前唯一单元测试通过：app 8、domain 5、network 7、ui 11、data 19、search 4、weather 11、shared weather-ui 2；新增 HTTP 回归覆盖配额策略迁移后的日／月配额、普通 429 和坏错误响应。所有模块 lint 无错误，保留既有版本／SDK 与布局提示。
- API 35 模拟器最终直接使用 SkyTestRunner 运行全部 19 项设备测试通过。期间一次 Gradle UTP 运行因 ADB 断连而中断；恢复后改用直接 instrumentation。修正原测试的异步等待：页面返回等待生命周期、加载阶段等待文本回调、ListAdapter 等待 AdapterDataObserver，避免把一度空闲当作异步渲染完成。
- R8 Release 应用及测试包构建通过，设备 RouteSmokeInstrumentation 通过 1 项：服务单例解析、共享会话、首页／搜索路由、迁移后的天气 XML 及 ICU 拼音正常。设备测试使用合成响应，不替代既有 API 24／36、区域网络和真实午夜验收。

## 2026-11-06 页面职责、泛型基类与注释整理

- 首页移除搜索／切换入口、结果契约和搜索等待状态；权限、设置返回、重试与会话校验由 HomeViewModel 管理。独立搜索页保留路由访问，选择防重入和历史提交由 SearchViewModel 处理。
- 页面状态、渲染器、适配器与供应商 DTO／接口统一命名。BaseActivity 和 BaseFragment 只需声明 ViewModel、ViewBinding 泛型，统一绑定创建、状态收集和销毁；生命周期测试覆盖中间泛型基类、视图重建、绑定释放以及 ViewModel 复用。
- 按类、方法和代码块补充中文注释，涵盖源码、测试、构建配置与 XML。注释批次的 118 个文件经过词法对比，除注释和空白外没有代码 token 变化；所有 Kotlin 方法均有相邻说明。
- `testDebugUnitTest :core:domain:test`：当前模块共 73 项单元测试通过，失败和错误均为 0。
- `:app:connectedDebugAndroidTest`：API 35 模拟器完整 18 项通过；覆盖泛型基类、首页恢复、独立搜索、历史、小时预报、空态、沉浸式边距和旋转。
- `:app:connectedReleaseAndroidTest -PtestBuildType=release -PlocalSmokeSigning=true -ProuteSmoke=true`：1 项压缩包冒烟通过，验证真实路由、泛型反射、Activity／Fragment 的 ViewBinding 加载与 ICU。UTP 未取到该项 logcat，但仪器结果与 Gradle 均成功。
- 最终 `:app:assembleDebug :app:assembleDebugAndroidTest` 和正常 `:app:assembleRelease` 构建通过；`lint --no-daemon` 通过。合并构建期间 Lint 曾读取正在重建的 kapt 路由文件而中断，改为先完成 Release 再独立运行 Lint 后通过，未禁用检查器或降低检查级别。
- 首轮设备回归发现空态清理范围误及非天气 Fragment，已恢复为仅移除 WeatherFragment。模拟器 System UI 无响应弹窗处理后重新执行；路由测试改用与实际路由一致的显式 Intent，避免 ActivityScenario 丢失重建跟踪。最终上述完整设备回归通过。
- 验证仍使用合成响应与 API 35 模拟器，不替代真实定位、目标设备矩阵、区域网络及真实午夜验收。

## 2026-11-06：恢复首页切换按钮

- 恢复首页右上角“切换”入口，加载、空态和天气展示时均可使用。独立搜索页通过结果契约返回原首页，选中后更新天气，取消保留已有地点；切换期间的启动暂停、恢复和旧响应隔离仍由 HomeViewModel 处理。
- app 与 feature:search 共 20 项单元测试通过；Debug 应用／测试包构建及两个模块的 Debug lint 通过。首次增量构建读取共享 UI 产物异常，清理该模块构建输出并禁用构建缓存后通过。
- API 35 模拟器全套 20 项 UI 测试首次通过 19 项，已有路由测试因未等待 onNewIntent 交付而提前断言。统一补齐会话交付等待后，HomeFlowTest 和 FoundationUiTest 共 9 项复跑全部通过，包括切换选择、取消、加载中切换、旋转与路由复用；新增切换用例在两轮均通过。
- 本轮只使用 emulator-5554，设备测试采用合成响应；未重复 Release 或真实网络验收。git diff --check 通过。
