# SOLID 与命名重构

本次优化针对已有多模块 Android 工程中的职责混合与宽接口，保留现有页面、服务路由、会话规则和天气请求时效。生产模块的直接依赖关系不变。命名采用“职责名词 + 角色”和“动作 + 业务对象”，不为了更长而替换已经明确的 Android 生命周期方法或通用 API。

## 五项原则的落地

| 原则 | 原问题 | 实施结果 |
| --- | --- | --- |
| 单一职责 SRP | 地点仓储同时处理搜索、系统逆地理编码和 ID 译名；执行器还解析 HTTP 错误 | 独立名称策略和 `HttpErrorClassifier`；请求执行器继续管理预算、重试、取消与共享限流 |
| 里氏替换 LSP | 完整天气接口以默认失败掩盖没有坐标天气能力；名称更新可能替换天气上下文 | 坐标查询单独声明且必须实现；设备与供应商名称策略保留来源、ID、坐标与时区；取消继续传播 |
| 开闭 OCP | 名称来源通过设备 / 非设备分支区分 | `ProviderLocationNameRepository` 根据注册表选择策略；新增来源修改装配注册即可；HTTP 分类可注入替换 |
| 接口隔离 ISP | 首页依赖全部天气方法，搜索依赖名称刷新，页面取得整组服务 | 引入坐标天气、已选地点天气、搜索和名称四个能力契约，以及三个功能依赖视图；测试替身无需填充无关空实现 |
| 依赖倒置 DIP | 功能层虽使用接口，但接口粒度仍由整体仓储决定 | ViewModel 只接受其使用的领域能力；`ApplicationServiceProvider` 和 `WeatherDependencyFactory` 集中装配实现；解析器与错误策略可替换 |

`LocationRepository` 和 `WeatherRepository` 保留为装配时的聚合契约。`HomeDependencies`、`SearchDependencies` 与 `WeatherDisplayDependencies` 是同一宿主服务的不同类型视图，所有页面仍共享会话、天气仓储和网络限流门。`ServiceLocator` 留在 Activity / Fragment 装配边界，不进入业务 ViewModel。

名称策略对未注册来源返回 `UNSUPPORTED_LOCATION`，避免误用其他供应商的地点 ID。设备名称空结果、异常、超时或身份不匹配时保留原地点；Open-Meteo 无效响应仍返回 `DATA_INVALID`。系统名称最多等待四秒，外部协程取消不转换为成功回退。

## 命名迁移

| 原名称 | 新名称 | 用意 |
| --- | --- | --- |
| `AppServices` | `ApplicationServiceProvider` | 明确应用依赖的服务提供者角色 |
| `WeatherRepositoryFactory` | `WeatherDependencyFactory` | 工厂实际装配仓储、会话与历史整组依赖 |
| `OpenMeteoMapper` | `OpenMeteoResponseMapper` | 明确供应商响应到领域模型的转换边界 |
| `LocationQueries` | `LocationQueryVariants` | 表达中文原词、行政后缀和拼音变体能力 |
| `RegionRequest` | `WeatherRegionRequest` | 明确天气区域请求控制器的作用域 |
| `WeatherRepository.currentAt` | `CoordinateWeatherRepository.getWeatherAt` | 按坐标取得地点及天气 |
| `current` / `forecast` / `hourly`（领域仓储） | `getCurrentWeather` / `getDailyForecast` / `getHourlyForecast` | 区分当前、逐日和逐小时数据 |
| `LocationRepository.search` / `refresh` | `searchLocations` / `refreshLocationName` | 区分搜索与仅更新展示名称 |
| 会话 `select` / `resolve` / `initialWeather` | `createSession` / `findLocation` / `findInitialWeather` | 明确创建动作、查找目标和缺失语义 |
| 历史 `recent` / `record` | `getRecentQueries` / `recordQuery` | 明确管理对象是搜索词 |
| 映射器 `location` / `current` / `forecast` / `hourly` | `mapLocation` / `mapCurrentWeather` / `mapDailyForecast` / `mapHourlyForecast` | 明确数据转换动作 |
| `LocationQueryVariants.variants` | `generateVariants` | 明确生成查询变体的行为 |
| `AndroidLocationNameResolver.resolve` | `resolveName` | 区分名称解析与位置获取 |
| `SearchViewModel.select` | `selectLocation` | 表达用户选择地点的意图 |

类文件、测试文件、调用点、路由冒烟源集和当前文档同步迁移；没有保留旧名称转发别名。上述属于项目内部 Kotlin 源码契约变更，外部源码消费者若存在，需要按此表迁移。HTTP 参数、路由地址和持久化键保持一致。历史验证记录中的旧名称保留历史含义。

## 23 种 GoF 模式的适用性

模式按真实变化点选择。下表区分本次采用、已有实现和暂不引入；工厂函数、数据类复制、枚举状态机等语言用法不等同于完整的 GoF 模式实现。

| 分类 | 模式 | 当前取舍 |
| --- | --- | --- |
| 创建型 | 工厂方法 Factory Method | 保留 `IWeatherService.createFragment` 页面创建边界；依赖工厂为集中装配函数，不额外添加抽象创建者继承体系 |
| 创建型 | 抽象工厂 Abstract Factory | 目前只有一套生产供应商组合，暂不引入工厂家族；由 `WeatherDependencyFactory` 集中组装 |
| 创建型 | 建造者 Builder | 沿用 OkHttp / Retrofit 自带 Builder；领域对象使用构造器，无需再包装 |
| 创建型 | 原型 Prototype | 不引入克隆协议；不可变状态使用 Kotlin `copy`，不宣称完整原型模式 |
| 创建型 | 单例 Singleton | 保留 ARouter 管理的进程级服务实例和惰性依赖；不在 ViewModel 中增加全局单例访问 |
| 结构型 | 适配器 Adapter | 保留 Android 定位 / Geocoder 适配；供应商响应由 `OpenMeteoResponseMapper` 转换为领域模型 |
| 结构型 | 桥接 Bridge | 尚无两组需要独立扩展的实现维度，暂不引入桥接层 |
| 结构型 | 组合 Composite | 业务没有树形天气对象，暂不引入统一叶子 / 容器协议 |
| 结构型 | 装饰器 Decorator | 尚无可叠加的仓储缓存或审计需求，暂不增加包装链；现有限流仍由共享执行器管理 |
| 结构型 | 外观 Facade | 保留宿主服务作为依赖装配入口，以功能视图限制消费者可见能力 |
| 结构型 | 享元 Flyweight | 无大量可共享细粒度对象的证据；共享连接池即可，不建立领域对象池 |
| 结构型 | 代理 Proxy | 沿用 Retrofit 生成的服务代理；不额外增加只转发的仓储代理 |
| 行为型 | 职责链 Chain of Responsibility | 名称来源具有唯一键，使用策略注册表即可；不增加有顺序歧义的处理链 |
| 行为型 | 命令 Command | 现有 `HomeUiEffect` 表达一次性平台操作；没有排队、撤销需求，不新增通用命令执行框架 |
| 行为型 | 解释器 Interpreter | 没有业务 DSL，暂不引入 |
| 行为型 | 迭代器 Iterator | 沿用 Kotlin 集合遍历，暂不自行实现 |
| 行为型 | 中介者 Mediator | 页面协调已集中在 ViewModel；不额外增加独立中介对象 |
| 行为型 | 备忘录 Memento | 地点与天气明确只保存在进程内，没有撤销或快照恢复需求，暂不引入 |
| 行为型 | 观察者 Observer | 保留 StateFlow 与生命周期感知的状态订阅，渲染层不主动拉取网络 |
| 行为型 | 状态 State | 现有枚举 / 不可变 UI 状态足够，不扩展为每个状态一个行为对象的 GoF 状态模式 |
| 行为型 | 策略 Strategy | 本次独立设备 / Open-Meteo 名称策略、来源分发与 HTTP 错误分类策略，变化点通过注入扩展 |
| 行为型 | 模板方法 Template Method | 保留 BaseActivity / BaseFragment 固定生命周期骨架与 Binding 钩子；不把业务流程放入基类 |
| 行为型 | 访问者 Visitor | 领域对象层次稳定且操作简单，暂不增加双分派和 visitor 接口 |

## 验证范围

新增名称策略测试覆盖注册扩展、注册表快照、未知来源、身份不匹配、坐标与时区保护及取消传播；HTTP 策略测试覆盖日期与非法 Retry-After、替换策略与共享冷却。既有测试继续验证搜索变体、超时、局部失败、小时 / 午夜窗口、会话与历史行为。宿主测试验证三个依赖视图复用同一组实例。

执行结果见 [工程验证记录](build-validation.md)。本地测试和构建不等同于所有 Android 设备、系统逆地理编码或真实网络验证。
