# SkyMoment 接口文档

整理日期：2026-11-07。搜索历史存储契约已同步本次 MMKV 实现；接口签名以本文链接的当前源码为准。

本文记录项目**实际使用**的 HTTP 接口、Android 系统接口及模块间调用契约。参数默认值、校验、重试和调用时机以当前源码为准；供应商完整能力可查阅各节的官方文档。以下 JSON 为说明结构的示例，数值不代表实时天气；小时和逐日示例仅展示部分条目。

## 1. 接口总览

应用直接访问 Open-Meteo 公共 HTTPS 服务，不经过自建后端。目前有 **5 类 HTTP 调用、3 个不同的 URL 路径**。

| 编号 | 功能 | 方法与地址 | Retrofit 方法 | 领域入口 |
| --- | --- | --- | --- | --- |
| GEO-01 | 搜索地点 | `GET https://geocoding-api.open-meteo.com/v1/search` | `OpenMeteoGeocodingService.search` | `LocationSearchRepository.searchLocations` |
| GEO-02 | 按 ID 更新地点名称 | `GET https://geocoding-api.open-meteo.com/v1/get` | `OpenMeteoGeocodingService.get` | `LocationNameRepository.refreshLocationName`，非设备地点 |
| WX-01 | 当前天气 | `GET https://api.open-meteo.com/v1/forecast` | `OpenMeteoForecastService.current` | `CoordinateWeatherRepository.getWeatherAt` / `getCurrentWeather` |
| WX-02 | 24 小时预报 | 同上 | `OpenMeteoForecastService.hourly` | `SelectedLocationWeatherRepository.getHourlyForecast` |
| WX-03 | 七日预报 | 同上 | `OpenMeteoForecastService.daily` | `SelectedLocationWeatherRepository.getDailyForecast` |

WX-01、WX-02、WX-03 使用不同的查询字段，分别发起请求、分别更新页面状态。项目没有把三个天气区域合并为一次请求，也未接入历史天气、空气质量等其他端点。

实现入口：[端点常量](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoErrors.kt)、[Retrofit 声明](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoService.kt)、[仓储实现](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoRepositories.kt)、[依赖装配](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/WeatherDependencyFactory.kt)。

## 2. 通用请求与响应约定

| 项目 | 当前实现 |
| --- | --- |
| 协议 | HTTPS；`NetworkFactory` 拒绝 HTTP 基地址 |
| 请求形式 | GET + URL Query，无请求正文；Retrofit 负责参数编码 |
| 鉴权 | 不发送 API Key、Authorization、签名头或账户专属 Host |
| 响应格式 | JSON；使用 kotlinx.serialization，忽略 DTO 未声明的字段 |
| 压缩与缓存 | OkHttp 处理透明 Gzip；未配置 HTTP 磁盘缓存 |
| 客户端复用 | 地点与天气仓储共享 OkHttpClient、RequestExecutor 和短期限流门 |
| 日志 | 不主动记录请求坐标 URL 或完整响应正文 |
| 语言 | 地理编码：`zh*` 归一为 `zh`，其他归一为 `en`；天气 HTTP 请求不发送 `language` |
| 单位 | 温度 °C、相对湿度 %、风速 m/s；天气代码由 UI 翻译 |
| 时间 | 当前天气和小时预报使用 Unix 秒；日预报使用当地日期字符串 |

本文参数表中的“项目取值”表示调用方当前传入的值，不代表供应商默认值。例如搜索固定请求 `count=50`，无需依赖供应商默认条数。地理接口语言回退和其他可选参数见 [Geocoding 官方文档](https://open-meteo.com/en/docs/geocoding-api)。

响应 DTO 的属性大多可空，缺失字段是否可接受由映射器决定；“HTTP 成功”不等于“有可展示数据”。接口层返回 `Response<DTO>`，业务层统一返回 `DataResult`，详见第 8 节。

## 3. GEO-01：地点搜索

**地址：** `https://geocoding-api.open-meteo.com/v1/search`

用于输入联想、提交搜索和点击搜索历史。用户必须从候选中选择地点，项目不会默认选择第一条同名结果。

### 请求参数

| 参数 | 类型 | 项目取值 / 规则 |
| --- | --- | --- |
| `name` | String | 用户关键词或仓储生成的查询变体；trim 后少于 2 个 Unicode 码点不发请求 |
| `language` | String | `zh` 或 `en` |
| `count` | Integer | 固定 `50`，是每个查询变体的条数上限 |
| `format` | String | 固定 `json` |

```bash
curl --get --connect-timeout 10 --max-time 20 \
  'https://geocoding-api.open-meteo.com/v1/search' \
  --data-urlencode 'name=London' \
  --data-urlencode 'language=en' \
  --data-urlencode 'count=50' \
  --data-urlencode 'format=json'
```

### 响应字段

| 字段 | 类型 | 用途与校验 |
| --- | --- | --- |
| `results` | Array | 地点候选；空数组表示无匹配结果 |
| `generationtime_ms` | Number | DTO 保留；当 `results` 缺失时，须存在此字段才被当前实现视为合法空响应 |
| `error` | Boolean | 缺省为 false；true 判为 `DATA_INVALID` |
| `results[].id` | Integer / Long | 地点 ID，必须存在；领域模型转换为字符串 |
| `results[].name` | String | 必须非空白 |
| `results[].latitude` | Number | 有限值，范围 `[-90, 90]` |
| `results[].longitude` | Number | 有限值，范围 `[-180, 180]` |
| `results[].timezone` | String | 必须能解析为 `ZoneId` |
| `results[].admin1` … `admin4` | String，可缺失 | 行政层级；展示顺序为 admin4 → admin3 → admin2 → admin1 |
| `results[].country` / `country_code` | String，可缺失 | 国家名称；`country` 为 null 时使用 `country_code` |
| `results[].error` | Boolean | 单个地点为 true 时丢弃该候选 |

```json
{
  "results": [
    {
      "id": 2643743,
      "name": "London",
      "latitude": 51.50853,
      "longitude": -0.12574,
      "timezone": "Europe/London",
      "admin1": "England",
      "admin2": "Greater London",
      "country": "United Kingdom",
      "country_code": "GB"
    }
  ],
  "generationtime_ms": 1.2
}
```

### 搜索与合并规则

- 输入变更防抖 350 ms；显式提交与点击历史立即搜索。相同查询和语言会进行请求去重，重试与语言变化可强制重新请求。
- 非中文查询通常发起 1 个 HTTP 请求。中文查询最多生成原词、去行政后缀、无声调拼音 3 个不同变体，并发查询；例如 `海淀区` → `海淀区`、`海淀`、`haidian`。
- 去后缀规则包含自治县、自治旗、新区、县、区、市；中文逗号转换为英文逗号，逗号后的地区限定保留。地名“都”按 dū 转写。
- 合并结果按地点 ID 去重，不额外截断为 50 条；部分变体失败时，仍返回其他变体找到的有效地点。
- 全部变体都没有有效候选时，有失败则返回失败，否则返回 `Empty`。非空候选数组全部校验失败也属于 `DATA_INVALID`。
- 行政路径去除空白、与地点名称相同的项和重复项。候选数量与覆盖范围取决于供应商，项目不保存城市数据库。

实现：[SearchViewModel](../feature/Search/src/main/java/com/example/skymoment/search/SearchViewModel.kt)、[LocationQueryVariants](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/LocationQueryVariants.kt)。

## 4. GEO-02：按 ID 获取地点

**地址：** `https://geocoding-api.open-meteo.com/v1/get`

用于已选 Open-Meteo 地点的名称语言更新或名称请求失败后的重试。设备定位产生的地点使用 Android Geocoder，不调用本接口。

| 参数 | 类型 | 项目取值 / 规则 |
| --- | --- | --- |
| `id` | String | 搜索结果的供应商地点 ID；不传本地会话 token |
| `language` | String | `zh` 或 `en` |

```bash
curl --get --connect-timeout 10 --max-time 20 \
  'https://geocoding-api.open-meteo.com/v1/get' \
  --data-urlencode 'id=2643743' \
  --data-urlencode 'language=zh'
```

成功响应是**单个地点对象**，没有 `results` 外层；字段与 GEO-01 的 `results[]` 相同：

```json
{
  "id": 2643743,
  "name": "伦敦",
  "latitude": 51.50853,
  "longitude": -0.12574,
  "timezone": "Europe/London",
  "country": "英国",
  "country_code": "GB"
}
```

除地点字段校验外，返回 ID 必须等于请求地点 ID，否则返回 `DATA_INVALID`。请求失败时天气页保留原地点和已有天气，并记录名称更新失败；不把失败响应作为新地点。供应商 ID 查询入口见 [Geocoding 官方文档](https://open-meteo.com/en/docs/geocoding-api)。

## 5. 天气接口共用规则

WX-01、WX-02、WX-03 均调用 `https://api.open-meteo.com/v1/forecast`。

| 共用参数 | 类型 | 项目取值 / 规则 |
| --- | --- | --- |
| `latitude` | Double | 设备坐标或所选地点纬度，按名称传参 |
| `longitude` | Double | 设备坐标或所选地点经度，按名称传参 |
| `timezone` | String | 首次设备坐标天气用 `auto`；已有地点使用 `location.timeZone.id`，例如 `Asia/Shanghai` |
| `temperature_unit` | String | 固定 `celsius` |

所有天气响应还需满足下列公共校验：

| 响应字段 | 当前映射规则 |
| --- | --- |
| `latitude` / `longitude` | 必须存在、有限且在合法范围；允许返回天气网格坐标，不要求与请求坐标完全相等 |
| `timezone` | 必须有效，且 `ZoneId.rules` 与查询地点的时区规则相同 |
| `error` | 缺省 false；true 判为 `DATA_INVALID` |
| `*_units` | 必须存在对应单位对象；字段单位逐项检查 |

`generationtime_ms`、`elevation`、`utc_offset_seconds`、`timezone_abbreviation` 等天气响应元数据未进入当前 DTO。项目按 `ZoneId` 转换小时显示时间；`auto` 由供应商根据坐标解析时区。供应商日预报的日期边界规则与客户端按时区裁剪是两个步骤，客户端不会重新计算供应商的日最高 / 最低温。参见 [Forecast 官方文档](https://open-meteo.com/en/docs)。

### 5.1 WX-01：当前天气

| 专用参数 | 类型 | 项目取值 |
| --- | --- | --- |
| `current` | String | `temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code,is_day` |
| `wind_speed_unit` | String | `ms` |
| `timeformat` | String | `unixtime` |

```bash
curl --get --connect-timeout 10 --max-time 20 \
  'https://api.open-meteo.com/v1/forecast' \
  --data-urlencode 'latitude=31.2' \
  --data-urlencode 'longitude=121.5' \
  --data-urlencode 'timezone=auto' \
  --data-urlencode 'current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code,is_day' \
  --data-urlencode 'temperature_unit=celsius' \
  --data-urlencode 'wind_speed_unit=ms' \
  --data-urlencode 'timeformat=unixtime'
```

```json
{
  "latitude": 31.18,
  "longitude": 121.46,
  "timezone": "Asia/Shanghai",
  "current_units": {
    "time": "unixtime",
    "temperature_2m": "°C",
    "relative_humidity_2m": "%",
    "wind_speed_10m": "m/s",
    "weather_code": "wmo code",
    "is_day": ""
  },
  "current": {
    "time": 1794016800,
    "temperature_2m": 18.7,
    "relative_humidity_2m": 57,
    "wind_speed_10m": 4.95,
    "weather_code": 1,
    "is_day": 1
  }
}
```

| `current` 字段 | 类型 | 单位 / 校验与领域字段 |
| --- | --- | --- |
| `time` | Long | 必须存在，且单位为 `unixtime`；不作为界面“获取时间” |
| `temperature_2m` | Double? | `°C`、有限值；允许负温度和 0 → `temperatureCelsius` |
| `relative_humidity_2m` | Double? | `%`、`0…100`，57 表示 57% → `humidityPercent` |
| `wind_speed_10m` | Double? | `m/s`、非负有限值；0 有效 → `windMetresPerSecond` |
| `weather_code` | Int? | `wmo code`、非负；UI 处理具体描述 → `weatherCode` |
| `is_day` | Int? | 0 为夜、1 为昼，其他值转 null → `isDay` |

`current`、`current_units`、合法 `time` 缺失时返回 `DATA_INVALID`。温度、湿度、风速、天气代码四项全部无效时返回 `Empty`；仅部分缺失则仍返回成功并标记 `partial=true`。昼夜标记不单独构成可展示的当前天气。

`getWeatherAt` 成功返回 `LocatedWeather(location, current)`：地点使用原设备坐标、响应时区、`provider="device"`、`id="current"`，初始名称为空，由 UI 显示“当前位置”。`getCurrentWeather` 返回已有地点的 `CurrentWeather`。两者的 `fetchedAt` 都取本地注入的 `Clock.instant()`。

### 5.2 WX-02：24 小时预报

| 专用参数 | 类型 | 项目取值 |
| --- | --- | --- |
| `hourly` | String | `temperature_2m,weather_code,is_day` |
| `forecast_hours` | Integer | `24` |
| `timeformat` | String | `unixtime` |

```bash
curl --get --connect-timeout 10 --max-time 20 \
  'https://api.open-meteo.com/v1/forecast' \
  --data-urlencode 'latitude=31.2' \
  --data-urlencode 'longitude=121.5' \
  --data-urlencode 'timezone=Asia/Shanghai' \
  --data-urlencode 'hourly=temperature_2m,weather_code,is_day' \
  --data-urlencode 'forecast_hours=24' \
  --data-urlencode 'temperature_unit=celsius' \
  --data-urlencode 'timeformat=unixtime'
```

```json
{
  "latitude": 31.18,
  "longitude": 121.46,
  "timezone": "Asia/Shanghai",
  "hourly_units": {
    "time": "unixtime",
    "temperature_2m": "°C",
    "weather_code": "wmo code",
    "is_day": ""
  },
  "hourly": {
    "time": [1794016800, 1794020400],
    "temperature_2m": [18.4, 18.8],
    "weather_code": [1, 1],
    "is_day": [1, 1]
  }
}
```

- `hourly.time` 为 Unix 秒数组 `List<Long?>`；温度为 `List<Double?>`，天气代码和昼夜为 `List<Int?>`，校验规则同当前天气相应字段。
- `hourly`、`time` 或 `hourly_units` 缺失、时间单位错误、任一存在的指标数组长度与 `time` 不同，均返回 `DATA_INVALID`。指标数组整体缺失时保留 null 指标。
- 按地点当地当前整点确定起点，以绝对时间连续生成 24 个小时。完全相同的重复记录合并；冲突、缺失或非法时间对应的位置保留空项，不把后面的小时向前挪。
- 24 个位置均无温度和天气代码时返回 `Empty`；否则返回 `HourlyForecast`，任一位置缺少温度或天气代码时 `incomplete=true`。上例仅为两条结构示例，不是完整的 24 小时响应。

本请求不发送 `wind_speed_unit`，因为未请求风速字段。Unix 秒转为 `Instant` 后再按地点时区显示，不手工叠加时区偏移。实现：[HourlyForecastWindow](../Domain/src/main/kotlin/com/example/skymoment/domain/HourlyForecastWindow.kt)。

### 5.3 WX-03：七日预报

| 专用参数 | 类型 | 项目取值 |
| --- | --- | --- |
| `daily` | String | `weather_code,temperature_2m_max,temperature_2m_min` |
| `forecast_days` | Integer | `7` |
| `wind_speed_unit` | String | `ms`，当前声明保留该参数，未请求日风速字段 |
| `timeformat` | String | `iso8601` |

```bash
curl --get --connect-timeout 10 --max-time 20 \
  'https://api.open-meteo.com/v1/forecast' \
  --data-urlencode 'latitude=31.2' \
  --data-urlencode 'longitude=121.5' \
  --data-urlencode 'timezone=Asia/Shanghai' \
  --data-urlencode 'daily=weather_code,temperature_2m_max,temperature_2m_min' \
  --data-urlencode 'forecast_days=7' \
  --data-urlencode 'temperature_unit=celsius' \
  --data-urlencode 'wind_speed_unit=ms' \
  --data-urlencode 'timeformat=iso8601'
```

```json
{
  "latitude": 31.18,
  "longitude": 121.46,
  "timezone": "Asia/Shanghai",
  "daily_units": {
    "time": "iso8601",
    "temperature_2m_max": "°C",
    "temperature_2m_min": "°C",
    "weather_code": "wmo code"
  },
  "daily": {
    "time": ["2026-11-07", "2026-11-08"],
    "temperature_2m_max": [18.8, 19.4],
    "temperature_2m_min": [14.7, 14.5],
    "weather_code": [3, 61]
  }
}
```

- `daily.time` 为 `yyyy-MM-dd` 日期字符串数组；最高 / 最低温为 `List<Double?>`，天气代码为 `List<Int?>`。
- `daily`、`time`、`daily_units` 缺失、日期单位错误或存在的指标数组长度错位，返回 `DATA_INVALID`。
- 日期不能解析则丢弃该条；温度须为有限摄氏值。最高温低于最低温时，两项均设为 null，不交换数值；三个指标全空的日期丢弃。
- 按地点时区保留今天 `D` 至 `D+6`；相同日期的完全相同条目去重，有冲突的日期丢弃，然后按日期排序。
- 无可用日期返回 `Empty`；不足 7 个有效日期仍可返回成功并标记 `incomplete=true`。示例只展示两天，完整请求期望七日窗口。

实现：[OpenMeteoResponseMapper](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoResponseMapper.kt)、[ForecastWindow](../Domain/src/main/kotlin/com/example/skymoment/domain/ForecastWindow.kt)。

## 6. Android 定位与地点名称接口

这两类调用通过 Android 平台完成，不存在项目自建的定位或逆地理编码 HTTP 地址。

| 能力 | 系统 API | 输入 | 输出 | 项目处理 |
| --- | --- | --- | --- | --- |
| 前台单次定位 | `LocationManagerCompat.getCurrentLocation` | 已授权且启用的定位 provider、取消信号、主线程 executor | 系统 `Location?`，转换为 `Coordinates?` | 首个有效位置用于请求天气 |
| 设备地点逆地理编码 | `Geocoder.getFromLocation` | 经纬度、`maxResults=1`、界面 Locale | 地址列表，转换为更新名称后的领域 `Location?` | 独立于天气加载，失败保留已有名称 |

定位需要 `ACCESS_COARSE_LOCATION` 或 `ACCESS_FINE_LOCATION`，不申请后台位置。候选包含 NETWORK、API 31+ 的 FUSED，以及具有精确位置权限时的 GPS，只使用当前启用的 provider。坐标须合法，且定位结果的单调时钟年龄在 0–120 秒内；成功或取消后释放所有候选回调。无权限、定位开关关闭、无候选或无有效结果返回 null；首页给定位阶段 15 秒预算。

逆地理编码先检查 `Geocoder.isPresent()`。API 33+ 使用异步回调，较低版本在独立线程执行阻塞接口；平台方法差异见 [Android Geocoder 文档](https://developer.android.com/reference/android/location/Geocoder)。仓储为名称解析设置 4 秒等待预算，超时不影响天气成功状态。名称依次从 `subLocality → locality → subAdminArea → adminArea → countryName` 提取，过滤空白并去重，不使用街道、门牌或 POI。

实现：[AndroidDeviceLocationProvider](../app/src/main/java/com/example/skymoment/AndroidDeviceLocationProvider.kt)、[AndroidLocationNameResolver](../app/src/main/java/com/example/skymoment/AndroidLocationNameResolver.kt)。

## 7. 页面调用顺序与请求数量

### 7.1 自动定位启动

1. 首页检查 / 请求位置权限，调用系统单次定位；拒权或定位失败时不请求天气。
2. 使用设备坐标调用 WX-01，`timezone=auto`，首页天气等待上限 30 秒。
3. 成功后在内存会话中保存地点与首屏当前天气，创建天气 Fragment。
4. 天气页复用首屏当前天气，独立请求 WX-02、WX-03，并异步通过 Geocoder 补充地点名称。

正常成功启动共 **3 次 Open-Meteo HTTP 请求**：当前天气、小时预报、日预报各一次；系统定位与 Geocoder 不计入该数量。首屏数据超过刷新阈值、重试或语言变化可能产生额外调用。

### 7.2 搜索并切换地点

1. GEO-01 进行 1–3 个查询变体请求，合并为候选列表。
2. 用户选择后创建新的内存会话，只回传 `session_token`；旧 token 失效。
3. 天气页分别请求 WX-01、WX-02、WX-03；初次展示搜索地点无需再调用 GEO-02。

无重试时通常为 **4–6 次 HTTP 请求**。名称语言更新时额外调用 GEO-02。每个可重试请求最多尝试两次，但实际次数还受总超时、取消和共享限流门影响；该数量是客户端请求数，不是供应商计费折算。

### 7.3 刷新与失败恢复

| 场景 | 当前行为 |
| --- | --- |
| 天气页返回前台 | 当前天气缺失或获取已满 10 分钟时刷新；日 / 小时预报缺失或已满 60 分钟时刷新 |
| 当地日期 / 小时变化 | 返回前台时重新校准窗口；前台跨午夜刷新日预报，跨整点刷新小时预报 |
| 界面语言变化 | 取消旧代次请求、更新地点名称并重新加载天气；天气 HTTP 本身仍不传语言 |
| 区域重试 | 只请求对应区域；在途同区域请求合并，失败保留已有数据，合法 `Empty` 清空该区域 |
| 启动阶段网络失败 / 超时 | 显示具体错误；网络恢复后返回前台可重新定位取天气，也可点击重试 |
| 手动选择与旧请求竞争 | 首页取消旧启动任务，并以代次校验防止迟到结果覆盖新选择 |
| 进程结束 | 地点和天气会话丢失，下次启动重新定位；只有搜索词历史持久化 |

时效阈值用于前台恢复判断，不表示后台每 10 / 60 分钟轮询。实现：[HomeViewModel](../feature/Home/src/main/java/com/example/skymoment/home/HomeViewModel.kt)、[WeatherViewModel](../feature/Weather/src/main/java/com/example/skymoment/weather/WeatherViewModel.kt)。

## 8. 结果、错误、超时与重试

### 8.1 业务结果

| 结果 | 含义 | 页面处理 |
| --- | --- | --- |
| `DataResult.Success<T>` | 数据有效，可能包含部分缺失字段 | 显示内容及缺失占位 |
| `DataResult.Empty` | 合法响应但无可用数据 | 显示空态；不等同于断网 |
| `DataResult.Failure(reason, retryAt)` | 网络、协议或业务校验失败 | 按原因显示错误；有旧天气时保留旧数据 |

`retryAt` 为可空 `Instant`，用于短期 429 冷却。原始响应 `reason` 不直接展示给用户。定义见 [WeatherModels](../Domain/src/main/kotlin/com/example/skymoment/domain/WeatherModels.kt)，文案映射见 [WeatherFailureText](../Common/src/main/java/com/example/skymoment/common/WeatherFailureText.kt)。

### 8.2 错误映射

| 来源 | `WeatherFailure` | 执行器自动重试 |
| --- | --- | --- |
| DNS、连接失败等 `IOException` | `NETWORK` | 至多 1 次 |
| `InterruptedIOException`、请求执行器超时 | `TIMEOUT` | 单次请求超时可重试 1 次；总预算耗尽直接结束 |
| HTTP 401 / 403 | `AUTHENTICATION` | 否 |
| HTTP 429，reason 包含 daily / monthly / quota（忽略大小写） | `QUOTA` | 否；当前代码不为它设置短期冷却时间 |
| 其他 HTTP 429 | `RATE_LIMITED` | 否；按共享冷却期阻止后续请求 |
| HTTP 500–599 | `SERVICE_UNAVAILABLE` | 至多 1 次 |
| 其他非成功 HTTP 状态，例如 400 / 404 | `REQUEST_REJECTED` | 否 |
| 成功 HTTP 无响应体、JSON 反序列化失败、映射结构校验失败 | `DATA_INVALID` | 否 |
| 名称刷新遇到未注册的地点来源 | `UNSUPPORTED_LOCATION` | 否；需在依赖工厂注册对应名称策略 |
| 协程取消 | 继续抛出 `CancellationException` | 不转换为业务失败，不重试 |

HTTP 错误响应通常可包含如下结构。当前实现仅在 429 分支读取 `reason` 做配额识别；其他错误按 HTTP 状态分类。

```json
{
  "error": true,
  "reason": "Example invalid request parameter"
}
```

成功状态码内的 `error=true` 则由 DTO / 映射流程按数据错误处理。供应商 HTTP 400 错误格式参见 [Geocoding 文档](https://open-meteo.com/en/docs/geocoding-api)。

### 8.3 超时预算与限流

| 层级 | 当前值 | 范围 |
| --- | --- | --- |
| OkHttp connect timeout | 10 秒 | 单次连接 |
| OkHttp read timeout | 15 秒 | 读取等待 |
| OkHttp call timeout | 20 秒 | 一次 HTTP 调用 |
| RequestExecutor | 45 秒 | 一次操作，含最多两次尝试和重试延迟 |
| 地点搜索仓储 | 45 秒 | 所有并发查询变体共用的外层总预算 |
| 首页定位 | 15 秒 | 系统定位等待 |
| 首页当前天气 | 30 秒 | `getWeatherAt` 总等待，可能先于执行器的 45 秒预算截止 |
| 设备地点名称 | 4 秒 | Geocoder 结果等待 |

自动重试仅适用于网络、请求超时及服务端 5xx，间隔随机 **500–1500 ms**。OkHttp 的连接失败自动重试已关闭，重试由执行器统一控制。上层预算可以提前取消底层工作，不能把各层时限相加当成必然等待时间。

短期 429 优先解析 `Retry-After` 的非负秒数，其次尝试 RFC 1123 日期；缺失、非法或溢出时使用当前时刻后 60 秒，最终至少冷却 1 秒。共享门只延长截止时间，不缩短已有冷却；冷却中不发新的 HTTP 请求。天气页另根据区域结果中的 `retryAt` 禁止区域重试并更新倒计时。冷却到期只解除限制，不保证立即自动重发；已经在途的其他请求也不会被这道门追溯取消。

实现：[NetworkFactory](../Datalayer/src/main/java/com/example/skymoment/datalayer/network/NetworkFactory.kt)、[RequestExecutor](../Datalayer/src/main/java/com/example/skymoment/datalayer/network/RequestExecutor.kt)、[OpenMeteoErrors](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoErrors.kt)。

## 9. 应用内部接口

以下是进程内 Kotlin / ARouter 契约，不是 HTTP 地址，也不是对外鉴权机制。

### 9.1 领域契约

| 接口 | 主要方法 / 属性 | 返回与用途 |
| --- | --- | --- |
| `LocationSearchRepository` | `suspend searchLocations(query, language)` | 候选地点列表，不自动选择 |
| `LocationNameRepository` | `suspend refreshLocationName(location, language)` | 只更新名称和行政路径；保留身份、坐标与时区 |
| `CoordinateWeatherRepository` | `suspend getWeatherAt(coordinates, language)` | `DataResult<LocatedWeather>`，必须显式实现 |
| `SelectedLocationWeatherRepository` | `getCurrentWeather`；`getHourlyForecast`；`getDailyForecast`，均为 suspend | 各区域独立的 `DataResult` |
| `LocationRepository` / `WeatherRepository` | 分别聚合上述地点 / 天气能力 | 供工厂与宿主装配，ViewModel 使用窄接口 |
| `DeviceLocationProvider` | `hasPermission()`；`suspend locate()` | Boolean / `Coordinates?` |
| `LocationSessionRepository` | `createSession(location, initialWeather?)`；`findLocation(token)`；`findInitialWeather(token)`；`clear()` | 创建 UUID token、解析当前地点、复用启动天气、清空会话 |
| `SearchHistoryRepository` | `getRecentQueries()`；`recordQuery(query)`；`clear()` | 最近 10 条有效关键词；忽略大小写去重，MMKV 保存 |
| `HomeDependencies` | `deviceLocation`、坐标 `weather`、`sessions` | 首页依赖视图 |
| `SearchDependencies` | 搜索 `locations`、`sessions`、`searchHistory` | 搜索依赖视图 |
| `WeatherDisplayDependencies` | 名称 `locations`、已选地点 `weather`、`sessions` | 天气依赖视图 |
| `WeatherDependencies` | 聚合搜索和天气视图 | 工厂的装配结果 |

方法完整签名见 [Repositories.kt](../Domain/src/main/kotlin/com/example/skymoment/domain/Repositories.kt) 和 [DeviceLocationProvider.kt](../Domain/src/main/kotlin/com/example/skymoment/domain/DeviceLocationProvider.kt)。新的地点选择使旧会话 token 失效；token、坐标、地点对象和天气不持久化。

`SearchHistoryRepository` 当前实现为 [MmkvSearchHistoryRepository](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/MmkvSearchHistoryRepository.kt)。首次访问初始化应用私有 MMKV 的单进程 `search_history` 实例，`queries` 保存 JSON 字符串数组；读写与清空在进程内串行执行。`recordQuery` 去除首尾空白，忽略不足两个 Unicode 码点的词，把重复词移到首位并限制为十条；`clear` 删除该键。损坏 JSON 读取为空，后续有效记录可以替换。详见 [持久化边界](data-contract.md#搜索历史与持久化边界)。

### 9.2 组件服务与页面结果

| 服务契约 / 路由 | 地址 | 输入与返回 |
| --- | --- | --- |
| `IApplicationService` | `/services/application` | 聚合 `HomeDependencies` 与 `WeatherDependencies`，页面按各自窄接口查找同一实例 |
| `ILocationSearchService.createSelectionIntent(context)` | `/location/service` | 返回选择地点模式的 `Intent` |
| `IWeatherService.createFragment(sessionToken)` | `/weather/service` | 返回天气 `Fragment` |
| 首页页面 | `/app/home` | 可接收 `session_token` |
| 搜索页面 | `/location/search` | 首页选择模式通过 Activity Result 返回 token |

`LocationSearchContract` 只接受 `Activity.RESULT_OK` 且 `session_token` 非空白的结果，取消或无效结果返回 null。只传随机会话标识，不传经纬度或完整天气对象。契约及地址见 [Core 服务目录](../Core/src/main/java/com/example/skymoment/core/service)、[ServiceRoutes](../Core/src/main/java/com/example/skymoment/core/router/ServiceRoutes.kt)、[LocationSearchContract](../Core/src/main/java/com/example/skymoment/core/router/LocationSearchContract.kt)。

## 10. 联调、排查与维护

上述 curl 命令用于手工检查单个外部请求，不包含应用的重试、共享限流和数据映射逻辑。检查 HTTP 状态及响应头时可增加 `-i`。固定日期的 JSON 示例仅用于理解协议，不能长期直接用作依赖当前时钟的日 / 小时窗口测试数据。

| 现象 | 优先检查 |
| --- | --- |
| 未到天气请求阶段 | 定位权限、系统定位开关、provider 是否可用，以及 15 秒内是否收到有效坐标 |
| 已定位但仍显示天气失败 | 设备侧 DNS / HTTPS 连接、请求超时、HTTP 状态、DTO 单位与时区；主机 curl 成功不能证明模拟器网络正常 |
| 有天气但名称仍为“当前位置” | 系统 Geocoder 的可用性和 4 秒名称解析预算；不表示天气失败 |
| HTTP 200 但返回 `Empty` / `DATA_INVALID` | 必要 envelope、单位、数组长度、实际可用指标和地点时间窗口 |
| 搜索找不到中文县区 | 原词 / 去后缀 / 拼音变体结果与行政路径；不要直接取第一个同名候选 |
| 请求未发出 | 输入长度、防抖、同请求去重、在途区域任务或共享 429 冷却 |

网络参数与真实 JSON 解析的现有测试见 [OpenMeteoHttpTest](../Datalayer/src/test/java/com/example/skymoment/datalayer/weather/OpenMeteoHttpTest.kt)，错误与重试见 [RequestExecutorTest](../Datalayer/src/test/java/com/example/skymoment/datalayer/network/RequestExecutorTest.kt)，启动恢复见 [HomeViewModelTest](../feature/Home/src/test/java/com/example/skymoment/home/HomeViewModelTest.kt)。测试执行入口见 [测试指南](testing.md)；设备 DNS 问题见 [工程验证记录](build-validation.md)。

修改接口时同步核对 Retrofit 参数、[DTO](../Datalayer/src/main/java/com/example/skymoment/datalayer/weather/OpenMeteoDto.kt)、映射规则、本文 curl 示例和对应测试。供应商历史联调结果见 [Open-Meteo 验证记录](open-meteo-validation.md)；其中较早的请求数量记录属于当时版本，当前包含小时预报的调用顺序与数量以本文第 7 节和源码为准。
