# Open-Meteo 数据契约

## 传输与结果

固定公共 HTTPS 主机为 api.open-meteo.com、geocoding-api.open-meteo.com。app 显式组装 ServiceFactory、两个 Retrofit 服务、共享 RequestExecutor、Clock 和内存会话；feature 不接触 Retrofit 或 DTO。当前天气、24 小时预报和七日预报使用同一 /v1/forecast 端点的不同字段请求，独立成功与失败。

无任何 Key、专属 Host、Authorization 或签名头。无正文／地点 URL 日志和 HTTP 磁盘缓存。OkHttp 负责透明 Gzip；连接／读取／单次调用超时 10/15/20 秒，操作总预算 45 秒。网络／5xx 至多重试一次，取消传播；短期 429 共享冷却，缺 Retry-After 为 60 秒，明确配额耗尽不自动重试。

## 地理与天气

- 地点保存供应商 ID、真实名称、行政路径、坐标和 IANA ZoneId，非法时区或坐标不进入候选。
- 中文最多查询原文、去行政后缀和 ICU4J 77.1 拼音，异步合并并按 ID 去重；即使原中文已有重名结果仍补查拼音。不会默认选择结果或保存城市库。地名“都”使用 dū 读音规则修正通用 ICU 的 xindou 结果；其他多音字仍受转换与供应商匹配能力限制。
- 相对湿度就是 0–100 的百分比，65 对应 65%；当前温度及风速 0 保留，负温度允许。单位未知或字段非法仅隐藏该指标；整体结构错误为数据异常。
- current.time 为模型有效时刻，fetchedAt 由 Clock 记录。天气保存 WMO code，由 UI 三语翻译。
- daily.time 是日期标签，与最大／最小温和天气代码数组严格对应；数组长度错位直接失败。按地点当地 D…D+6 筛选、去重、排序，冲突日丢弃。高温低于低温时两值缺失，不交换。
- hourly 请求 `temperature_2m,weather_code,is_day`、`forecast_hours=24`、`timeformat=unixtime` 和摄氏温度；按 [Open-Meteo 文档](https://open-meteo.com/en/docs) 将绝对时间转为地点时区。数组长度必须对应，未知单位或缺失字段保留占位；按当前当地整点至未来 23 小时筛选，缺失／冲突时段不挤占后续小时的位置。
- 新选择使旧 token 失效；会话不落盘，进程重启重新搜索。

## 数据归因

天气由 [Open-Meteo](https://open-meteo.com/) 提供，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。地理检索由 [Open-Meteo Geocoding](https://open-meteo.com/en/docs/geocoding-api) 提供，地名数据来自 [GeoNames](https://www.geonames.org/)。领域模型保留来源元数据；按用户要求，天气页面和搜索候选均不显示来源页脚，来源链接保留在本文档与 README 中。

## 已执行验证

NetworkFactory/RequestExecutor 使用 HTTPS MockWebServer 验证中文编码、Gzip、未知字段、HTTP 分类、重试、429、坏 JSON、总截止和取消。ForecastWindow 使用固定 Clock 验证跨时区、DST、午夜、重复冲突、缺日；映射与查询变体测试使用合成数据。实时供应商证据另见 [验证记录](open-meteo-validation.md)，自动化测试不代替真实区域和午夜验收。
