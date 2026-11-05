# Design

## Context

动机见 [proposal.md](proposal.md)。已只读检查根构建文件、版本目录、Manifest、资源及两个模板测试：当前仅有 `:app`，Manifest 无 Activity/INTERNET 权限，无业务代码、网络库或路由库；现有测试仅验证加法及包名。当前目录不是 Git 仓库，因此本轮没有可用的 Git 差异基线。

配置声明 AGP 9.3.3、Gradle 9.5.0，minSdk 24、compileSdk 36.1、targetSdk 36；Gradle daemon 使用 JDK 21，源码兼容目标为 Java 11。本轮未运行构建，不能据此断言依赖已解析或版本组合可用。现有 OpenSpec 主规格为空。

用户已确认：只交付 Android App、客户端直连并接受明确的凭据提取风险；简繁英三语，其他系统语言回退英文；中国支持县/区，海外以可检索行政区域为准；页面采用 XML + ViewBinding。本轮没有提供和风天气专属 API Host、有效凭据或跨区域测试环境，供应商结论限于文档核验。

## Goals / Non-Goals

**Goals:**

- 用可替换的仓储接口隔离供应商，业务 UI 不接触 Retrofit DTO、鉴权或供应商错误码。
- 用单向数据流表达独立区域的加载、内容、错误与过期状态，确保切换地点、语言和日期时结果一致。
- 保持组件边界与依赖方向可检查；把真实 API 与构建工具链的高风险验证排在业务实现之前。

**Non-Goals:**

- 不建设通用插件框架、独立发布每个组件或多后端调度；不为每个卡片、模型或接口单独建模块。
- 不把客户端混淆、NDK 或本地加密视为秘密不可提取的保证；不引入用户账户或后台服务绕开已确认的直连范围。
- 产品范围外能力以 proposal 为准；本设计不包含生产部署和购买服务。

## Decisions

### 1. 八个模块及单向依赖

| 模块 | 职责 | 可依赖的本项目模块 |
| --- | --- | --- |
| `:app` | Application、启动入口、依赖组装、环境配置和路由初始化 | 所有下述模块，仅此层组装实现 |
| `:core:domain` | 纯 Kotlin 领域模型、错误类型、仓储/会话接口、日期窗口策略 | 无 |
| `:core:network` | Retrofit/OkHttp 构建、超时、响应解析基础设施、脱敏 | 无 |
| `:core:ui` | XML 主题与样式、加载/空/错布局和 Views、通用格式化与本地化资源 | 无 |
| `:core:navigation` | 路由常量、Navigator 门面、ARouter 适配 | 无 |
| `:data:weather` | 和风 DTO、映射、Geo/天气服务、仓储与当前会话实现 | domain、network |
| `:feature:search` | 搜索 Activity、XML 布局/ViewBinding、列表适配器、ViewModel 和搜索 UI 状态 | domain、ui、navigation |
| `:feature:weather` | 天气 Activity、XML 布局/ViewBinding、列表适配器、ViewModel 和两个天气区域 | domain、ui、navigation |

feature 之间禁止依赖，core 不得引用 feature/data/app；data 不引用 UI。使用 `implementation` 默认隐藏实现，只有接口确实需要时开放 `api`。接口放在 domain，不另建空的 API 模块；未来出现独立复用或发布需求再分拆。

选择显式构造器注入和 app 层小型 AppContainer，由公共接口提供 ViewModelFactory 所需依赖；业务 ViewModel 不访问全局可变服务。相较首版加入 Hilt 注解处理器，显式组装降低与现有 AGP/KAPT 的耦合；将来可替换组装方式而不改变业务接口。

### 2. Kotlin、XML Views、MVVM 和 StateFlow

按用户要求采用 AppCompatActivity + XML 布局，UI 模块启用 ViewBinding，以 Material Components for Android 控件和 XML 主题/样式统一外观，使用 ConstraintLayout 组织布局。搜索结果与七日预报采用 RecyclerView + ListAdapter/DiffUtil，列表项也使用 XML/ViewBinding；通用加载、空数据、错误布局放在 core:ui，通过 include 或封装 View 复用。ViewBinding 仅用于视图访问，不承载业务逻辑；不增加 Data Binding 双向表达式。绑定方式遵循 [ViewBinding 文档](https://developer.android.com/topic/libraries/view-binding)。

ViewModel 私有 MutableStateFlow、公开不可变 StateFlow，Activity 在 `lifecycleScope` 中通过 `repeatOnLifecycle(Lifecycle.State.STARTED)` 收集并调用幂等 render 更新 Views；多个 Flow 在该作用域内分别启动子协程收集。收集器恢复时只渲染最新状态，不因重复订阅重新触发网络请求；点击、重试、输入和刷新作为意图传入，网络和日期业务在仓储/领域策略内完成。生命周期处理参考 [StateFlow 官方说明](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)。

整理根和 app 的构建插件、功能开关及依赖，清理无引用的版本目录项，按用途明确声明 Kotlin 工具链版本。UI 层统一使用 AppCompat、Material Components、ConstraintLayout、RecyclerView、ViewBinding 及所需 Lifecycle KTX，测试使用 Espresso 和 ActivityScenario。

- SearchUiState：原始 query、有效语言、Idle/Loading/Content/Empty/Error、候选列表及错误。输入 trim 后防抖 350ms、去重；一个中文字符也允许搜索，空字符串不请求。键盘提交可立即查询但须合并同一在途请求。
- WeatherUiState：地点会话、语言、地点时区、current 与 forecast 两个独立区域状态。区域含数据、错误、isRefreshing、fetchedAt 和不完整标记；首次请求加载，无数据失败显示错误；已有数据失败保留数据并显示未更新。
- 使用 `viewModelScope` 及可取消挂起请求。每次输入、地点、语言变化产生请求代次；取消旧作业并在回写时核对代次，防止不可及时取消的旧响应覆盖新状态。取消不映射成业务错误。
- 当前天气和每日预报并行获取，使用独立结果和监督作用域，单个请求失败不取消另一请求。今日高低温从 forecast 的今天数据派生，不发第三个天气请求。
- 旋转保留 ViewModel 状态，不重复发请求；从后台恢复只在语言、当地日期变化或当前天气距获取超过 10 分钟/预报超过 60 分钟时刷新对应区域。这是前台刷新策略，不是长期缓存或后台轮询。
- Activity 的绑定、监听器和适配器只属于该 Activity 生命周期，不存入 ViewModel 或单例；页面销毁时释放相关引用。渲染输入框时只在文字实际变化时更新，防止 TextWatcher 与状态回写互相触发；列表提交使用不可变快照。
- 不使用 SavedStateHandle、View 自动状态保存或磁盘缓存保存 GeoAPI 返回内容，相关动态 View 不保存这些值；进程丢失后重新搜索。普通 UI 临时状态与配置变化由会话内状态承接。

### 3. ARouter 仅负责组件入口

跨组件入口为 `/location/search` 和 `/weather/detail`，各由 feature 自己的 AppCompatActivity 加载 XML 布局并通过 ViewBinding 渲染。app 启动入口路由到搜索；搜索选择地点后进入天气，天气页提供重新搜索入口；返回行为避免重复堆叠搜索页。Navigator 门面集中传参和错误处理，页面不散布原始路由字符串。

选择地点写入 domain 接口定义的内存会话，由 data 实现，路由仅携带随机的会话 token；真实地点名称、坐标、ID 和时区不进入 Intent 持久恢复快照。token 不具备鉴权作用；进程重建 token 失效时安全回到搜索。切换新地点创建新 token，避免数据串用。

[ARouter 官方仓库](https://github.com/alibaba/ARouter)描述 Kotlin 注解处理与可选注册机制；[Android 官方迁移说明](https://developer.android.com/build/migrate-to-built-in-kotlin)指出 AGP 内建 Kotlin 下传统 kotlin-kapt 不兼容，可采用同版本 `com.android.legacy-kapt`。因此先验证已发布 ARouter api/compiler 与 legacy-kapt，设置唯一模块名，不将尚未发布的 KSP 实现当作稳定依赖。首版先用默认路由发现，不引入额外 register 插件；对实际 release 包测试路由发现与 R8 保留规则。若存在工具链硬冲突，记录具体错误并修订规划，不默默替换 ARouter 或全局降级 AGP。

### 4. 和风天气接入结论与契约

2026-11-05 核验的官方文档如下；这张表不等同于成功调用记录。

| 目的 | 接口与选择 | 结论 |
| --- | --- | --- |
| 搜索 | `GET /geo/v2/city/lookup?location=…&lang=…&number=20`，不限定 range | [文档](https://dev.qweather.com/docs/api/geoapi/city-lookup/)支持全球搜索，返回地点身份、行政区、坐标和时区；真实县区命中待测 |
| 当前天气 | `GET /weather/v1/current/{latitude}/{longitude}?lang=…&localTime=true` | [文档](https://dev.qweather.com/docs/api/weather/weather-current/)支持所需当前指标 |
| 七日预报 | `GET /weather/v1/daily/{latitude}/{longitude}?days=7&lang=…&localTime=true` | [文档](https://dev.qweather.com/docs/api/weather/weather-daily-forecast/)支持 1–10 天；本产品固定请求七天，起止日期与午夜响应需验证 |
| 主机 | `https://<账户专属 API Host>/` | 按[API Host 文档](https://dev.qweather.com/en/docs/configuration/api-host/)配置，不使用旧共享域名 |
| 认证 | `X-QW-Api-Key` 请求头 | [认证文档](https://dev.qweather.com/docs/configuration/authentication/)目前支持 API Key；保留认证适配边界，2027 年限制必须核验 |

不采用即将弃用的 v7 城市天气接口，也不把新版 v1 的 `temperature.value`、比例湿度、带单位风速等字段按旧版字符串 DTO 处理。GeoAPI 仍是独立响应格式，包含业务 code 与 location；两者使用不同 envelope。序列化采用 Retrofit + kotlinx.serialization，忽略未知附加字段但严格校验已使用字段，不能把整个错误响应反序列化为全 null 的成功数据。OkHttp 使用内建 Gzip 处理，不手工设置导致重复压缩/解压的头。

搜索保持供应商排序，按位置 ID 去重，显示名称/adm2/adm1/国家并移除相邻重复名称。`type=city` 不能用于证明一定是地级市或县区；县区验收依赖人工确认的目标行政实体，不对供应商未提供的层级编造标签。用户选择前不加载天气，不默认选首项。

领域模型建议包括 Location（provider、id、name、行政路径、坐标、ZoneId）、CurrentWeather、DailyForecast、WeatherAttribution、WeatherFailure；不向 feature 暴露供应商 DTO。坐标发送 v1 时按两位小数规范化、使用小数点且顺序为纬度/经度，保留原始地点 ID 和名称；不能直接复用 GeoAPI 的“经度,纬度”参数排列。

映射规则：温度统一 °C、风速 m/s、湿度比例乘 100；识别上游 unit 后再转换，未知单位记为缺失而非猜测。温度允许负数和零；风速非负、湿度比例 0–1；高温低于低温时该日高低温标为异常，不交换伪装修复。缺失个别字段为局部缺失；全部核心字段缺失为无可展示内容；损坏 JSON、错误字段类型和错误 envelope 为解析失败。获取时刻由可注入 Clock 记录，界面写“获取于”；v1 当前天气文档未给出观测时间，不移植 v7 的 obsTime 假设。

### 5. 客户端凭据与供应商使用限制

按用户确定的纯客户端范围，首版采用 API Key 直连；不在 APK 放置 JWT 签名私钥，不用无法自动续期的手工 JWT 冒充长期方案。开发/发布使用不同 Key，配置来自未入库本地属性或 CI 秘密变量，由 app 注入网络层。Host 不是秘密保护机制；合法 HTTPS Host 缺失/错误及 Key 缺失时，release 构建失败，debug 给出配置错误，不自动连接示例地址。

[安全指南](https://dev.qweather.com/docs/best-practices/security-guidelines/)提供 API/应用限制：为 Key 仅开放必要接口，配置包名及签名证书约束；直连请求带 `X-Android-Package-Name`、`X-Android-Cert`。这些信息可被伪造，不能把它们当作不可绕过的验证。验证 debug/release 签名差异，开启额度通知并提供服务侧撤销及客户端发版轮换步骤；用户无需登录或创建和风账户。源码不含 Key 并不代表 APK 不含 Key。

日志关闭请求/响应正文，清除 Authorization、X-QW-Api-Key、查询地点和坐标，仅记录端点种类、耗时、分类错误及非敏感请求标识。GeoAPI 不写 HTTP 磁盘缓存、数据库、搜索历史、备份或崩溃附件；仅保留当前 UI 使用所需的内存对象。[缓存说明](https://dev.qweather.com/docs/best-practices/cache/)限制地理信息存储，因此首版不增加地点持久化；天气也不启用磁盘缓存，降低过期和跨日复杂度。

搜索和天气页显示可点击 QWeather/和风天气名称，天气页还展示 `metadata.attributions` 返回的声明/链接，保留 GeoAPI 的许可元数据供来源说明使用；遵循[来源要求](https://dev.qweather.com/docs/terms/attribution/)。使用链接仅作为来源信息，不自动执行响应内容。[定价说明](https://dev.qweather.com/docs/finance/pricing/)为按量计费；免费额度按服务分组而非每个端点各有一份，实施时核对当前账户，不承诺永久免费。

### 6. 错误、重试和状态转换

HTTP 状态先判断，再区分 Geo 业务码与 v1 problem+json 中的错误类型；依据[错误文档](https://dev.qweather.com/docs/resource/error-code/)解析，不直接显示供应商 detail。以下超时/冷却数值是本产品初始策略，非供应商 SLA。

| 类别 | 处理 |
| --- | --- |
| Geo 合法空列表/明确无地点；合法无天气数据 | Empty 或 UnsupportedLocation，提示修改查询/稍后重试 |
| 断网、DNS、超时 | Network/Timeout；首次及至多一次有抖动的延迟重试，随后可手动重试 |
| HTTP 5xx | ServiceUnavailable；至多一次自动重试 |
| 401、403、普通参数错误、404 路径错误 | 分为凭据/额度/配置问题；不自动重试，不显示为空结果 |
| 429 | 区分短期频率和月度配额；短期限流遵守 Retry-After，无头则至少 60 秒冷却，跨页面共享；月度配额不定时自动重试 |
| 响应解析或不支持单位/时区 | 全局结构错误为 DataInvalid；单字段错误为局部缺失；不自动重试 |
| CancellationException | 传递取消，不发错误提示 |

connectTimeout 10 秒、readTimeout 15 秒、每次 callTimeout 20 秒，自动重试延迟约 0.5–1.5 秒；单次用户操作整体截止 45 秒，避免多层重试相乘。统一在一层控制重试，禁用或核算 OkHttp 额外连接重试；401 不尝试隐式登录。刷新按钮合并重复操作，重试只针对失败区域；限流冷却期间禁用再次请求并给出本地化说明。成功后清除该区域错误并更新 fetchedAt。

### 7. 日期窗口与国际化

以可注入 Clock 和 Location.ZoneId 求 D，使用 LocalDate.plusDays 构造 D…D+6；每日项依据 forecastStartTime 转到地点时区后的日期归属，并验证 forecastEndTime 的排他结束边界与当地日历。该归属策略需要用真实 v1 样本验证，若数据不提供可映射的完整当日窗口，则供应商验证不通过，不取“前七条”充数。重复完全相同日可去重，冲突日丢弃并标记不完整；窗口外日丢弃。午夜触发及恢复前台重新计算，不以固定 24 小时计时替代当地下一次午夜。API 24/25 通过 core library desugaring 支持 java.time，需测试而不是提高 minSdk 避开。

默认资源为英文，添加简体、繁体资源；集中 LocaleResolver 按系统首选 Locale 的脚本/地区映射三种有效语言，资源上下文和 API 参数共用结果，避免 Android 资源匹配和网络语言各自判断。系统首选语言不支持则固定英文，不取后续语言意外改变规则。数字显示用有效 Locale，请求坐标用固定小数点格式。语言变化时旧响应失效，重新查询 Geo 显示名称及天气；失败可保留数字但不沿用旧语言天气文本。供应商合法的地点名称回退保留。

### 8. 验证矩阵与完成标准

| 层面 | 样本/方法 | 通过标准 |
| --- | --- | --- |
| 中国粒度 | 海淀区、德清县、昆山市；朝阳重名结果 | 每个县区能独立选择且行政归属正确，重名可区分；“全部县区覆盖”不能由这三个样本推断 |
| 海外覆盖 | London、New York、Tokyo；另查 Shinjuku 观察细分支持 | 城市可选择；细分查无结果时提示上级城市，不宣称县区命中 |
| 三语 | zh-hans、zh-hant、en 查询同一组样本 | UI 和请求参数正确，供应商文本回退被记录 |
| 天气契约 | 每个选定样本获取 current 与 daily | 必要指标可展示，今日高低温同源，七日日期为 D…D+6；记录纬经顺序、湿度/单位及归因 |
| 网络区域 | 中国大陆固定宽带及移动网络各一处，至少一处海外实际网络；不依赖 VPN | 每种网络做 10 次“搜索→两天气请求”流程，至少 9 次在 10 秒内完成，超时能恢复；这是首版验收门槛，非全球可用性保证 |
| 时间边界 | 注入时钟测试纽约与北京异日、纽约夏令时、当地午夜、后台恢复 | 七个日历日不偏移；缺日/重复/跨日刷新失败符合规格 |
| 错误与竞争 | MockWebServer + 协程测试调度器 | 无结果、超时、HTTP/业务失败、非法 JSON、单接口失败、慢旧请求、快速切换、限流均有确定结果 |
| 工程与 UI | 模块依赖检查、单测、lint、debug/release 构建、Espresso/ActivityScenario、API 24 和 target API 设备 | 无依赖环，UI 依赖符合 XML/ViewBinding 技术方案，页面由 StateFlow 驱动，ARouter 能打开两个入口，旋转/进程重建安全，三语完整 |

真实验证记录单独存放后续开发文档，只保存人工输入样本、非敏感断言结果和耗时，不把完整 GeoAPI 响应当作测试夹具。自动化夹具使用合成数据。未提供凭据或某地区网络时保留未验证项，不以模拟结果替代跨区域实测。

UI 验证采用 [Espresso](https://developer.android.com/training/testing/espresso) 和 ActivityScenario，列表使用 RecyclerViewActions，网络/协程异步状态使用可控 Fake 或 IdlingResource 同步，不以固定 sleep 等待。覆盖停止时不更新已失活 Views、恢复后渲染最新状态、旋转无重复订阅，以及 XML 布局的三语长文本显示。

## Risks / Trade-offs

- [纯客户端无法保密 API Key，且 2027 年 API Key 限制临近] → 用户已选择此范围；实施先核实受限额度是否满足验收，限制接口、区分环境、监控和轮换；如不足，暂停供应商集成交付并提交范围变更，不暗自添加后端。
- [全球搜索不等于每个海外县区均可检索] → 以真实名称和行政路径展示，细分缺失有明确提示；中国样本失败则不通过核心验收，扩大目标县区抽样后再声明覆盖范围。
- [ARouter 与当前 AGP/Gradle、Views 依赖组合未经构建验证] → 完成最小 XML 页面、ViewBinding、路由、KAPT、配置缓存和 release 冒烟，记录确切可用版本；失败先查明确的兼容点。
- [GeoAPI 不提供通用行政层级字段且限制缓存] → 不以 type 猜层级，不建离线库；进程结束后用户需要重新搜索，这是首版明确体验取舍。
- [v1 新契约、账户权限或地区连通性不满足要求] → 文档核验只算候选通过，真实验证为后续集成前置；保留仓储接口替换空间，但不在本变更自动接入第二供应商。
- [两个天气请求完成时间不同] → 分区状态、独立获取时间与同地点/语言代次校验；今日高低温只来源于当前有效预报。

## Migration Plan

这是从空壳新增能力，无用户数据库迁移。本轮停在规划；实施时先完成 API/工具链验证并建立 XML/ViewBinding 基础，再建模块和数据层，随后实现搜索、天气、国际化及验收。引入依赖锁定到已验证版本，不无理由改写现有工具链。

没有生产流量切换。实施过程中按可构建阶段保留版本基线；若供应商验证失败，保留可用工程骨架并暂停相应业务交付，修订设计后继续。凭据泄露时在和风控制台撤销受影响 Key，替换配置并发版，旧版本会显示服务不可用；不承诺无后端条件下远程无感更换 Key。

## Open Questions

- 实施环境的专属 API Host、开发/发布凭据及签名指纹待配置，不能写入本规划。
- 大陆宽带、移动网络和海外实测节点的具体运营商与地点待安排，验收样本与门槛已确定。
- UI 视觉细节采用 Material Components for Android 的 Views 控件和 XML 主题/样式，具体配色、间距和图标样式可在实现时确定，不影响规格和模块边界。
