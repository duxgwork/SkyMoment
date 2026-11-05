# Proposal

## Why

SkyMoment 目前只有 Android 工程骨架，尚无地点搜索或天气业务。面向中国大陆和海外用户建立无需登录的天气查询流程，让用户选择地点后查看当前天气及包含当地今天的七日预报，同时为后续功能保留清晰的组件边界。

## What Changes

- 新增地点搜索与选择：中国支持县、县级市和区；海外按供应商可检索的当地行政区域展示，没有对应细分区域时明确提示，不自动冒充县区级结果。
- 新增当前天气页面，显示天气现象、温度、今日预报最高/最低温、湿度、风速；可用时补充体感温度、风向，并显示获取时间和来源。
- 新增从所选地点当地今天开始的连续七日预报，定义跨时区、跨日、缺失日期和部分失败的处理。
- 新增加载中、请求失败、空数据、局部失败、刷新失败保留已有数据等状态；页面状态由 ViewModel 的只读 StateFlow 驱动。
- 首版仅 Android，使用 Kotlin、XML Views + ViewBinding、Retrofit/OkHttp、协程和 ARouter；按基础设施、数据与业务功能拆分 Gradle 模块。
- 界面跟随系统选择简体中文、繁体中文或英文，其他语言回退英文；API 语言同步映射。
- 优先验证和风天气 GeoAPI 与 Weather v1，使用账户专属 API Host。用户已选择客户端直连 API、不建设后端；明确 API Key 可被提取及配额滥用风险，采用凭据隔离、限制和轮换控制影响。
- 本变更先交付规划；实际请求验证、凭据配置、兼容性试验和业务开发均列入后续实施任务。

范围外：用户账户、后端代理或签发服务、自动定位及定位权限、搜索历史和地点收藏、离线城市库、持久化天气缓存、小时预报、空气质量、预警、推送、桌面组件、手动语言/单位设置、多天气源自动切换、应用商店发布。首版统一显示 °C、m/s、%。

## Capabilities

### New Capabilities

- `location-search`：匿名进入、全球地点搜索、县区粒度、重名辨识、选择和切换地点。
- `current-weather`：当前天气指标、今日高低温、来源和获取时间，以及独立刷新状态。
- `seven-day-forecast`：地点当地今天起的七日预报、日期完整性与跨日更新。
- `weather-data-access`：供应商接入、凭据配置、错误分类、请求生命周期、数据有效性及真实服务验收。
- `system-localization`：系统语言映射、界面与 API 文本、日期数字格式和回退行为。

### Modified Capabilities

无。当前 `openspec/specs/` 尚无已有能力规格。

## Impact

- 后续影响 `settings.gradle.kts`、根与 app 构建配置、版本目录、Manifest、资源和测试；增加 core、data、feature 模块。当前没有 Activity 或业务代码可迁移。
- 引入外部 API 账户、可用凭据和配额管理，但应用最终用户无需账户或自带 Key。公开 API 不等于匿名或无限免费 API。
- 现有配置为 AGP 9.3.3、Gradle 9.5.0、minSdk 24 / targetSdk 36；目标页面使用 XML、AppCompat、Material Components for Android、RecyclerView 和 ViewBinding。版本兼容与路由编译处理器需实测，不能从配置文件存在推断已构建成功。
- 文档核验日期：2026-11-05。和风天气[旧版城市天气接口](https://dev.qweather.com/docs/api/weather/weather-now-webapi-v7/)已有弃用提示；采用[实时天气 v1](https://dev.qweather.com/docs/api/weather/weather-current/)与[每日预报 v1](https://dev.qweather.com/docs/api/weather/weather-daily-forecast/)。[认证文档](https://dev.qweather.com/docs/configuration/authentication/)披露自 2027-01-01 起 API Key 每日请求数量将受限制，需在实施阶段核实账户适用额度。
- 本轮只增加 OpenSpec 规划文件，不修改项目代码或构建配置。
