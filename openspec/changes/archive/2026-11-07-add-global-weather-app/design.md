# Design

## Context

动机见 [proposal.md](proposal.md)。工程基础已经建立，当前拆分为九模块：XML/ViewBinding、ARouter、显式注入、StateFlow、内存会话和三语状态组件。debug/release 构建、配置缓存、14 项基础单元测试、debug UI 及混淆包路由冒烟已有证据，详见开发验证文档。

用户已确认改用 Open-Meteo，无 API Key、账户专属 Host 或后端；七日列表每天一个天气概况及高低温，不再展示独立日夜概况。最新截图改为浅灰背景、白色圆角卡片、左对齐温度及紧凑七日列表。首次启动即进入搜索与天气同屏的首页；无所选地点标签、全页刷新按钮、预报获取时间或天气来源页脚，保留当前天气获取时间和区域重试。三段切换控件仍不添加。

工具链为 JDK 21、Gradle 9.5.0、AGP 9.3.3、compileSdk 37（满足保留的 core 1.19.0 元数据要求），minSdk 24、targetSdk 36、Java 11。Android 模块启用 desugar_jdk_libs 2.1.5。公开端点已完成本机无凭据请求，但完整区域验证未完成。

## Goals / Non-Goals

**Goals:**

- 供应商 DTO、网络错误与页面隔离，保留可替换仓储边界。
- 所选地点、语言、日期与响应严格一致，两个天气区域独立完成和恢复。
- 无凭据配置即可运行，明确公开服务限制和实际覆盖证据。
- 搜索历史只持久化最近十个有效关键词，使用 MMKV，支持清空；地理结果与天气会话保持内存边界。

**Non-Goals:**

- 不引入用户账户、后端、离线城市库、多源自动切换或通用插件框架。
- 不把模型天气标为气象站观测，不补造县区译名、日夜天气或缺失预报。
- 不自动购买商业 API 服务；当前使用公共非商业端点开发和验证。

## Decisions

### 1. 分层与显式组装

九模块职责、直接依赖及运行时装配见 [分层架构图](../../../../docs/architecture.md)。基础 UI／导航／网络层不依赖领域业务，天气展示移到 shared:weather-ui，供应商组装与协议解析由 data:weather 负责。feature 互不依赖，不引用 app 或数据实现；domain 保持纯 Kotlin/JVM。

SkyMomentApplication 只在 onCreate 初始化 ARouterNavigator。AppServices 是 ARouter 缓存的进程单例，提供领域 WeatherDependencies 和 app 私有的 AppDependencies；页面经通用 ServiceLocator 获取契约，再以构造器注入 ViewModel。删除混合层次的 ApplicationServices、无消费者的 Navigator 接口和重复 ServiceFactory 封装。数据工厂共享连接池与限流门；仪器测试动态替换服务路由。生产构建不含 Fake，不额外引入 Hilt。

### 2. XML、状态和生命周期

AppCompatActivity / Fragment + Material Components Views + RecyclerView/ListAdapter/DiffUtil + ViewBinding。私有 MutableStateFlow、公开 StateFlow。首页 WeatherFragment 持有独立 ViewModel，搜索使用独立 Activity，并在 viewLifecycleOwner.lifecycleScope/repeatOnLifecycle(STARTED) 内幂等渲染；绑定和监听器在 onDestroyView 清理，不在重新订阅时请求。独立 Activity 路由保留 Activity 作用域的同一规则。WeatherContent 复用当前天气与预报渲染，不持有请求仓储。

搜索输入 trim、防抖 350ms、distinct，键盘提交立即查询并合并相同在途操作；少于两个 Unicode 字符时提示继续输入，不发送供应商确定无结果的请求。空输入清空状态。每次查询、地点或语言改变提升代次，取消旧任务并在回写时核对代次。

天气 current 与 forecast 在监督作用域内并发独立加载；同一区域已有数据时刷新不清空，失败保留原 fetchedAt 并标记未更新。今日高低温只来自同一 forecast 的当地今天。刷新合并在途操作，单区重试只请求失败区域；不展示全页刷新按钮，正常数据通过前台恢复和跨日规则更新。前台恢复：当前天气超过 10 分钟、预报超过 60 分钟或日期变化时刷新；不后台轮询。

### 3. 路由与会话

主入口为 HomeActivity，在 Manifest 中直接声明 MAIN / LAUNCHER 和 exported=true；保留 /app/home 路由供应用内导航，ARouter 在 SkyMomentApplication 中初始化，不再设置中转 Activity。首页展示 WeatherFragment，右上角切换通过 LocationSearchContract 打开浅色独立 SearchActivity；选择后回传随机会话 token，替换天气 Fragment，不增加天气页返回栈。启动先定位并获取天气，加载使用完整插画与阶段提示，失败显示对应空态。

保留 /app/home 和 /location/search 路由，删除重复的 WeatherActivity 与 /weather/detail。普通搜索路由选择后也回到首页，已有首页通过 onNewIntent 更新；首页打开的搜索继续通过 Activity Result 回传。Routes 只定义地址，NavigationExtras.SESSION_TOKEN 定义传参键。随机 token 关联内存 Location，新选择使旧 token 失效；旧天气 Fragment 销毁并取消任务，旋转保留 Fragment ViewModel。进程丢失后首页重新定位，失败显示空态。Geo 信息不写入 SavedStateHandle、View 自动状态、Intent 或备份。路由返回使用 CLEAR_TOP/SINGLE_TOP。

ARouter api/compiler 1.5.2 + AGP 对应 legacy-kapt 9.3.3，模块名 app_home / feature_search（天气模块不再使用路由处理器），默认 dex 路由发现，无 register 插件。删除 feature_weather 路由模块时 versionCode 从 2 升至 3，覆盖安装会按 ARouter 版本规则更新路由缓存。Jetifier 处理旧 support 引用；consumer 规则保留生成表、facade、反射创建的 IProvider 默认构造器及 ISyringe。RouteMeta.rawType 仅注解阶段引用的 javax.lang.model.element.Element 单独 dontwarn。

debug 使用 Espresso/ActivityScenario；release 使用平台 RouteSmokeInstrumentation 验证实际路由和 XML。仅本机 smoke 额外保留注入合成会话的 RouteSmokeData 桥接类，不增加 ARouter 保留范围，保持 R8 开启。API 24 与 target API 设备验收仍独立追踪。

### 4. 公共端点和天气契约

核验日期 2026-11-05，依据 [Forecast 文档](https://open-meteo.com/en/docs) 和 [Geocoding 文档](https://open-meteo.com/en/docs/geocoding-api)。

| 用途 | 请求 |
| --- | --- |
| 搜索 | https://geocoding-api.open-meteo.com/v1/search?name=…&count=50&language=zh或en&format=json |
| 地点译名更新 | https://geocoding-api.open-meteo.com/v1/get?id=…&language=zh或en |
| 当前天气 | https://api.open-meteo.com/v1/forecast，current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code,is_day |
| 七日预报 | 同一 forecast 端点，daily=weather_code,temperature_2m_max,temperature_2m_min，forecast_days=7 |

两个天气操作分别请求相应字段，以保留独立失败／重试语义；并不增加第三个高低温请求。均使用 latitude / longitude 命名参数、temperature_unit=celsius、wind_speed_unit=ms、timezone=所选地点 IANA ID。当前时间使用 timeformat=unixtime，逐日时间使用 iso8601 日期标签。坐标以固定小数点发送，保留地理服务精度，不套用其他供应商的两位小数约束。

当前天气单位从 current_units 校验；温度允许负值和零，湿度原生为 0–100%，不乘 100，风速为非负 m/s。当前 time 是模型有效时刻，界面主时间仍为注入 Clock 记录的“获取于”，不标为站点观测时间。Open-Meteo 会选择邻近模型网格，响应坐标不要求与地点坐标逐字相等；请求上下文保持 Location 身份，防止串用地点。

逐日数据为 daily.time 与各字段平行数组，按索引对应。必要 time 结构损坏为 DataInvalid；可选指标缺项显示缺失，错位／超长结构不能静默 zip 截断后宣称完整。解析日期标签为 LocalDate，按所选 ZoneId 求 D，筛选 D…D+6、排序、去重；完全相同日可去重，冲突日丢弃并标记不完整，高温小于低温时该日高低温不可用。全部核心指标为空时为空数据；损坏 envelope 不是空数据。

Open-Meteo 文档说明 ISO 时间与日聚合采用请求时解析的 UTC offset，区间内 DST 不重新计算。日期标签用于识别供应商逐日产品，不把其日聚合区间伪称严格的 DST 本地午夜区间；应用自身七日窗口、今天标记、日期显示和下一次午夜调度始终使用 IANA ZoneId 与日历运算，不通过固定 24 小时计算日期。真实样本和测试记录这一供应商限制。

### 5. 地理检索与中文别名

地理服务来自 GeoNames，返回 id、name、latitude、longitude、timezone、country、admin1…admin4 和 feature_code；缺失字段不编造。位置身份使用供应商 ID。feature_code 仅辅助类型识别，不证明全国县区全覆盖。

本机样本：海淀、德清可返回对应行政中心；海淀区、德清县、新都区全称无结果；中文昆山返回多个同名村镇而江苏目标可由 Kunshan 查询得到。适配层最多生成原文、去末尾单个行政后缀、拼音三种不同查询，有限并发并在同一个 45 秒操作预算内执行，按 ID 合并；拼音使用 ICU4J 77.1 Han-Latin 后去声调和空格（兼容 minSdk 24），保留原查询与真实供应商译名，不维护硬编码城市库。必要时可补充查询中明确的国家／省级限定。所有候选显示完整可用行政路径，保留单次供应商排序，不默认选第一条，不把拼音同音结果当作唯一匹配。

原文可得结果但缺少目标时也补查拼音，避免“昆山”已有错误同名结果导致跳过补查。原始输入不足两个字符时不请求；两字为精确匹配，三字以上按供应商前缀规则。去后缀后不足两个字时保留原文，不过度截断。中国完整县区覆盖仍需实测；失败不得仅用同名上级市通过验收。

### 6. 语言、WMO 与来源

LocaleResolver 只取系统首选语言，Hans/Hant 脚本优先，无脚本时 TW/HK/MO 繁体，其余中文简体，其他语言英文。资源与日期数字格式使用同一有效 Locale。应用固定打包三语资源。

Geocoding 参数简繁均用 zh、英文用 en。供应商中文译名可能混用字形或回退拉丁名，保留有效返回名称，不编造或硬替换译名。天气接口没有语言参数，domain 保存 WMO code / isDay，core:ui 在渲染时映射简繁英描述及图标；未知代码显示通用占位，切换语言立即更新本地天气文字。地点译名按 ID 重查；旧语言请求失效，离线时已有数值与新语言天气描述可保留，提示译名刷新失败；下次返回前台时重试译名，不依赖已移除的全页按钮。

按用户要求移除天气来源页脚，README 和数据契约保留 Open-Meteo / CC BY 4.0 归因与服务说明，领域模型仍保留静态来源。搜索候选可见时提供 Open-Meteo Geocoding、GeoNames 和 CC BY 4.0 链接。链接只作来源，不执行返回文本。

### 7. 无凭据网络、错误和配额

固定两个公共 HTTPS base URL，不读天气 Key／Host 环境变量，不添加鉴权／Android 签名头。release/debug 不存在凭据缺失门槛，release 禁止明文端点。OkHttp 内建透明 Gzip，不额外重复解压，不建磁盘缓存；关闭正文和含地点／坐标的 URL 日志。

connectTimeout 10 秒、readTimeout 15 秒、callTimeout 20 秒；每次用户操作整体 45 秒。统一一层处理重试，禁用隐式连接重试，只对网络／5xx 至多自动重试一次，延迟 0.5–1.5 秒。CancellationException 原样传播。HTTP 400 或 error=true 为请求／数据错误；401/403、404 路径错误不自动重试。合法缺 results 或无天气指标为空，不能把错误载荷解析为全 null 成功。

429 短期限流遵守 Retry-After，缺失则至少 60 秒；共享冷却限制所有页面。明确日配额耗尽不自动定时重试。reason 只用于安全分类，不原样展示。单位、时区或整体结构损坏是 DataInvalid；字段级问题是局部缺失。

公共服务 [使用条件](https://open-meteo.com/en/terms) / [定价](https://open-meteo.com/en/pricing) 限定非商业用途，当前公开限额为每分钟 600、每小时 5,000、每日 10,000 次，并有公开月预算。调用可能按变量和日期范围折算，不承诺每次用户查询只有一个计费单位。服务位于海外，免 Key 不代表大陆网络稳定或商用授权；首版开发可使用公共端点，不自动购买或引入客户专属 API。

### 8. 验证矩阵

| 层面 | 方法与门槛 |
| --- | --- |
| 中国县区 | 海淀／海淀区、德清／德清县、昆山／昆山市、新都区；朝阳重名。验证后缀与拼音补查、独立身份、行政归属与显式选择 |
| 海外 | London、New York、Tokyo、Shinjuku，记录细分支持或无结果，不保证全球最细级覆盖 |
| 语言 | UI 简繁英及法语回退、脚本优先、多语言首选；Geo zh/en 回退、WMO 三语、运行中变化 |
| 天气 | 无 Key 的 current/daily 请求、百分比／单位／零值、七个当地日期、模型时间与归因 |
| 网络 | 大陆宽带、大陆移动及至少一处实际海外网络，各 10 次完整流程，至少 9 次在 10 秒内；不依赖 VPN，不推断未知当前网络区域 |
| 时间 | 可注入 Clock，纽约与北京异日、DST、当地午夜与前台恢复，刷新失败剔除昨天 |
| 错误 | MockWebServer、协程虚拟时钟：400/error=true、429、5xx、超时、坏 JSON、部分成功、取消、旧响应、45 秒截止 |
| 工程/UI | 模块依赖、单元测试、lint、debug/release、配置缓存、API 24 与 target API 设备；Espresso/Fake/IdlingResource，不用固定 sleep |
| 搜索历史 | Android 设备加载真实 MMKV JNI：跨实例与文件重开、Unicode 码点、忽略大小写去重、十条上限、损坏 JSON 恢复及清空；业务触发由 SearchViewModelTest 验证 |

仅保存人工查询样本、非敏感断言和耗时。自动化夹具使用合成数据；API 35 的基础验证不代替 API 24 / target API 的最终设备验收。

### 9. 搜索历史与持久化边界

搜索词由 Domain 的 `SearchHistoryRepository` 定义，Datalayer 的 `MmkvSearchHistoryRepository` 实现，`ApplicationServiceProvider` 注入搜索依赖。feature 只依赖契约，不直接使用 MMKV。依赖版本由 `gradle/libs.versions.toml` 固定为 `com.tencent:mmkv:1.3.17`，采用 1.3.x LTS 保留 32 位设备兼容性。

仓储首次读写时使用 Application Context 初始化 MMKV，以 `SINGLE_PROCESS_MODE` 打开 `search_history`，在 `queries` 键保存 JSON 字符串数组。多个仓储实例共享进程内锁，避免读改写交错；这不是跨进程事务设计。`encode` 失败时报告存储错误，不误报为写入成功。

仅在显式提交、点击历史标签或选中候选时记录当前关键词；trim 后少于两个 Unicode 码点的输入忽略，包含单个 emoji 的过短输入也不记录。忽略大小写去重，重复使用移到首位并保留本次大小写，最多十条。防抖输入、重试与语言变化不新增历史；关键词保留用户输入，不随界面语言翻译。

缺少 `queries` 或 JSON 损坏时返回空列表；读取时过滤无效词、去重并限制条数，下一次有效写入可覆盖损坏值。清空只删除该键，重新打开存储后仍为空。地点对象、坐标、搜索结果、天气和会话 token 不写入 MMKV，HTTP 无磁盘缓存，应用继续关闭备份。

搜索关键词可跨实例及存储重开保留。历史词只能用于重新搜索，不恢复旧地点选择或天气。

五项 `MmkvSearchHistoryRepositoryTest` Android 仪器测试覆盖真实 JNI 和关闭重开后的文件状态；相关本地单元测试继续验证服务装配与搜索业务。实际执行结果见 [工程验证记录](../../../../docs/build-validation.md)，不能据此推断强制杀进程、断电恢复或完整设备矩阵已验证。

## Risks / Trade-offs

- 中文别名、县区中心与同音结果存在缺口 → 后缀／拼音补查、完整行政路径、人工矩阵与明确空提示，不假装全覆盖。
- 模型当前天气与逐日固定 offset 聚合不是站点观测 → 保留来源、正确时间标签与供应商约束，应用日期窗口独立使用 IANA 日历。
- 免费服务限额、非商业用途和大陆连通性 → 明确用量、限流和区域验收，不以无凭据冒烟代替生产可用性。
- 旧路由库依赖 Jetifier / 反射 → 固定已验证版本、精确 keep 与 release 设备冒烟；升级 AGP 前重新验证。

## Implementation Plan

保留已验证工程基础，移除旧供应商规划、配置提示与语言码假设；数据层接入两个公共端点。先实现并测试契约、中文别名和 WMO 映射，再接通完整搜索／天气／刷新行为，最后完成设备及区域实测。

## Open Questions

- 大陆宽带、移动和海外实测节点的运营商／区域待安排，不阻止公共端点实现，仍阻塞完整地区验收。
