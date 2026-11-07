# 测试指南

所有生产组件均在各自 `src/test` 中维护本地单元测试，测试与实现同模块，可以验证 internal 行为。Domain 保留 `src/test/kotlin`；其他组件使用 `src/test/java`。测试支撑模块 Test 使用 `src/main/kotlin` 对测试消费者公开工具，并用自己的 `src/test/kotlin` 验证这些工具。

## 分层与复用

- `Test → Domain`：复用领域契约，提供确定性样本和会话替身。
- `app / Core / Common / Datalayer / framework / feature:*` 通过 `testImplementation(project(":Test"))` 引用，不进入生产编译或运行时。
- Domain 直接使用 JUnit，避免 `Domain → Test → Domain` 的依赖环。
- Test 不依赖数据实现或功能层。Home 测试通过 FakeLocationSessionRepository 控制会话，不再跨层引用 Datalayer。

`MainDispatcherRule` 在每个测试前替换 Dispatchers.Main，结束或失败后恢复。使用 `runTest(rule.dispatcher)`，确保 ViewModel 与测试共用调度器。`SchedulerClock` 随该调度器的虚拟时间推进，`withZone` 后仍保持同一时间轴。时间边界测试不使用 sleep。

`WeatherFixtures` 每次创建固定时刻的样本；`FakeLocationSessionRepository` 产生确定性 token，模拟旧会话失效和初始天气复用。只把多个模块需要的能力放入 Test，供应商 DTO 和局部行为替身留在对应测试中。

## 覆盖矩阵

| 组件 | 重点行为 |
| --- | --- |
| Domain | 经纬度合法性、结果映射、地点日期、午夜、七日和 24 小时窗口、DST、重复及缺失数据 |
| Datalayer | HTTP 状态、超时与取消、限流冷却、TLS 本地请求、DTO 映射、地点查询和拼音、名称策略注册与身份保护、HTTP 分类策略替换、仓储结果、会话失效；搜索历史的 MMKV 持久化与损坏恢复由 Android 仪器测试覆盖 |
| Core | 搜索结果的成功／取消／空 token，服务类型检查、缺失服务、缓存和 Intent 委托 |
| Common | 天气格式化、零值与缺失值、天气码、错误类型和三语资源 |
| framework | 语言解析、资源上下文隔离、Activity／Fragment Binding 生命周期、状态面板与重试监听 |
| feature:Home | 权限、定位与天气超时、失败重试、恢复、手动选择优先级、前后台取消与一次性事件 |
| feature:Search | 防抖、去重、提交、语言代次、迟到响应、历史与重复选择 |
| feature:Weather | 三个区域独立加载、局部失败、共享冷却、午夜／整点刷新、旧响应隔离、初始天气复用与空态 |
| app | 系统定位权限、关闭定位、近似定位、坐标回调与取消、逆地理编码、应用依赖复用与功能依赖视图共享 |
| Test | 主调度器、时区时钟继续推进、会话替身状态隔离 |

纯业务测试使用 JUnit 与 kotlinx-coroutines-test；Android API／资源测试使用 [Robolectric](https://robolectric.org/getting-started/)，固定 API 35 沙箱，并在 app 测试替换为普通 Application，避免自动装配路由和真实服务。网络测试仅访问本机 MockWebServer，不依赖公共天气接口。首次运行需要下载 Maven 依赖和 Robolectric Android 沙箱。

搜索历史测试 `MmkvSearchHistoryRepositoryTest` 位于 `Datalayer/src/androidTest`，在设备或模拟器上加载真实 MMKV JNI。五项用例覆盖跨实例读取与大小写去重、关闭重开后的持久化、十条上限与 Unicode 码点、损坏 JSON 恢复、读取清理与清空后重开。其余数据层测试继续在本地 JVM 运行。

MMKV 测试验证仓储和真实文件读写，不代替搜索页面 UI 回归、强制杀进程或断电恢复测试。

## 执行

```bash
# 所有组件的本地单元测试，包括 Test；不包含 MMKV 仪器测试
./gradlew testDebugUnitTest
# 单个组件或用例
./gradlew :Core:testDebugUnitTest
./gradlew :feature:Home:testDebugUnitTest --tests '*HomeViewModelTest'
# MMKV 原生存储测试（需连接 Android 设备或模拟器）
./gradlew :Datalayer:connectedDebugAndroidTest
# 连接多个设备时，明确选择测试目标（示例序列号）
ANDROID_SERIAL=emulator-5556 ./gradlew :Datalayer:connectedDebugAndroidTest
# 发布变体
./gradlew testReleaseUnitTest
# 检查测试工具没有进入应用运行时
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

每个组件的本地测试 HTML 报告在 `build/reports/tests/testDebugUnitTest/index.html`，JUnit XML 在 `build/test-results/testDebugUnitTest/`。MMKV 仪器测试 HTML 报告在 `Datalayer/build/reports/androidTests/connected/debug/index.html`，XML 在 `Datalayer/build/outputs/androidTest-results/connected/debug/`。无源码任务不计为测试通过。此矩阵说明已测试的行为，不代表 100% 行覆盖率，也不替代真机权限、系统 Geocoder、真实网络或页面端到端验证。
