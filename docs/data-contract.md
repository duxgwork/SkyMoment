# Open-Meteo 数据契约

## 传输与结果

固定公共 HTTPS 主机为 api.open-meteo.com、geocoding-api.open-meteo.com。app 通过 Datalayer 的 WeatherDependencyFactory 获取领域依赖，由工厂组装两个 Retrofit 服务、共享 RequestExecutor、Clock 和内存会话；feature 不接触 Retrofit 或 DTO。当前天气、24 小时预报和七日预报使用同一 /v1/forecast 端点的不同字段请求，独立成功与失败。完整参数、响应示例和调用顺序见 [接口文档](api-reference.md)。

无任何 Key、专属 Host、Authorization 或签名头。无正文／地点 URL 日志和 HTTP 磁盘缓存。OkHttp 负责透明 Gzip；连接／读取／单次调用超时 10/15/20 秒，操作总预算 45 秒。网络／5xx 至多重试一次，取消传播；短期 429 共享冷却，缺 Retry-After 为 60 秒，明确配额耗尽不自动重试。

## 地理与天气

- 地点保存供应商 ID、真实名称、行政路径、坐标和 IANA ZoneId，非法时区或坐标不进入候选。
- 中文最多查询原文、去行政后缀和 ICU4J 77.1 拼音，异步合并并按 ID 去重；即使原中文已有重名结果仍补查拼音。不会默认选择结果或保存城市库。地名“都”使用 dū 读音规则修正通用 ICU 的 xindou 结果；其他多音字仍受转换与供应商匹配能力限制。
- 相对湿度就是 0–100 的百分比，65 对应 65%；当前温度及风速 0 保留，负温度允许。单位未知或字段非法仅隐藏该指标；整体结构错误为数据异常。
- current.time 为模型有效时刻，fetchedAt 由 Clock 记录。天气保存 WMO code，由 UI 三语翻译。
- daily.time 是日期标签，与最大／最小温和天气代码数组严格对应；数组长度错位直接失败。按地点当地 D…D+6 筛选、去重、排序，冲突日丢弃。高温低于低温时两值缺失，不交换。
- hourly 请求 `temperature_2m,weather_code,is_day`、`forecast_hours=24`、`timeformat=unixtime` 和摄氏温度；按 [Open-Meteo 文档](https://open-meteo.com/en/docs) 将绝对时间转为地点时区。数组长度必须对应，未知单位或缺失字段保留占位；按当前当地整点至未来 23 小时筛选，缺失／冲突时段不挤占后续小时的位置。
- 新选择使旧 token 失效；会话不落盘，进程重启重新定位获取当前天气，也可由用户手动搜索地点。

## 搜索历史与持久化边界

搜索历史由 `SearchHistoryRepository` 定义，Datalayer 的 `MmkvSearchHistoryRepository` 实现。MMKV 仅保存用户使用过的查询词，不保存搜索结果、地点对象、经纬度、时区、天气或会话 token；查询历史不能恢复已选地点或旧天气。

| 项目 | 当前约定 |
| --- | --- |
| 存储 | 应用私有 MMKV，单进程实例 `search_history`，字符串键 `queries` |
| 格式 | JSON 字符串数组，按最近使用顺序排列，最多十条 |
| 输入 | 去首尾空白；不足两个 Unicode 码点不记录，单个 emoji 也视为过短 |
| 去重 | 忽略大小写，重复使用移至首位，保留本次提交的大小写 |
| 写入时机 | 显式提交、点击历史标签或选中候选；输入防抖、重试和语言刷新不新增记录 |
| 读取与恢复 | 缺少键或 JSON 损坏时返回空列表；读取时过滤无效词、去重并限制条数，下一次有效写入可替换损坏内容 |
| 清空 | 删除 `queries`，重新打开存储后仍为空 |

关键词保留用户输入，不随系统语言翻译或替换。应用关闭备份，HTTP 仍无磁盘缓存；MMKV 历史不改变地理与天气数据的内存会话边界。`MmkvSearchHistoryRepositoryTest` 使用真实 Android JNI 验证存储，执行方式见 [测试指南](testing.md)。

## 数据归因

天气由 [Open-Meteo](https://open-meteo.com/) 提供，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。地理检索由 [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api) 提供，地名数据来自 [GeoNames](https://www.geonames.org/)。领域模型保留来源元数据；按用户要求，天气页面和搜索候选均不显示来源页脚，来源链接保留在本文档与 README 中。

## 已执行验证

NetworkFactory/RequestExecutor 使用 HTTPS MockWebServer 验证中文编码、Gzip、未知字段、HTTP 分类、重试、429、坏 JSON、总截止和取消。ForecastWindow 使用固定 Clock 验证跨时区、DST、午夜、重复冲突、缺日；映射与查询变体测试使用合成数据。实时供应商证据另见 [验证记录](open-meteo-validation.md)，自动化测试不代替真实区域和午夜验收。
