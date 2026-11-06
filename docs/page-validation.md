# 页面状态与验证映射

页面使用 XML/ViewBinding。首页和搜索页背景延伸至透明系统栏，内容避让状态栏、导航栏、刘海及软键盘；边距不随重复分发累加。首次启动进入浅色天气首页，右上角“切换”打开独立搜索页；首页不内嵌搜索框与结果列表：浅灰背景、白色圆角卡片、左对齐温度及紧凑七日列表。保留湿度／风速；七日列表上方增加横向滑动的 24 小时预报。

## 行为

搜索输入保留在 ViewModel 内存中：trim 后至少两字符、350ms 防抖，点击搜索或键盘 Search 立即提交；相同查询／语言已完成时不重复请求。清空和语言变化递增代次，迟到结果不能恢复旧内容。候选显示供应商名称、完整可用行政路径，由用户选择后生成随机 token；HomeActivity 原地替换天气 Fragment，独立搜索页关闭，不增加返回栈。启动时先尝试前台定位和当前位置天气，加载期间以完整插画、文案、不定进度与定位／天气步骤展示当前阶段；拒权、定位失败／超时、当前天气失败或为空时显示对应原因的整页空态，提供开启定位／重试操作。独立搜索页左上角返回按钮与系统返回行为一致；从首页进入时取消保留原地点，未完成的自动定位可恢复。手动选择优先于自动请求；七日预报失败不触发首页回退。

当前天气、24 小时预报和七日预报各自持有数据、加载、空及错误状态。首次加载不填默认天气；刷新保留已有数据，失败显示“未更新”，各区域获取时间只用于内部时效判断，界面不显示获取时间和搜索来源页脚，所有区域均无有效天气且请求结束时展示整页空态并支持重试；部分区域仍有数据时保留内容。不提供全页刷新按钮，前台恢复和跨日触发刷新，区域失败或空数据提供重试；内部刷新合并仍在进行的请求；429 冷却对三个区域共用，冷却期间禁用操作。高低温来自同一份逐日预报中当地今天的记录，明确标为“今日预报”。

小时窗口按地点时区从当前整点起连续 24 小时，按绝对时间推进以处理夏令时重复小时；缺失或冲突时段保留占位，温度为 `--`。整点和跨小时回到前台时更新，失败保留窗口内已有数据，小时区域可单独重试。

七日窗口为地点时区 D…D+6。缺日不补造，冲突日剔除，缺少今天时不借明天的高低温。每天仅一个 WMO 概况。不完整预报直接提供该区域的重试按钮，恢复七天不会额外请求当前天气。午夜使用 IANA 日历下一天的 startOfDay；退后台停止计时，前台恢复先剔除过期日期，再按当前天气 10 分钟／预报 60 分钟时效刷新。旋转使用同一 ViewModel，不重复请求新鲜数据。

系统语言变化使 Activity 重建资源上下文，使用有效语言标签标记请求代次。Geo 参数统一 zh/en；天气代码和“当前位置”标题就地翻译，不依赖天气接口的文案。译名失败保留已有名称并显示提示，再次回到前台时重试译名。地点对象、坐标和天气数据不进入 Intent、SavedState、磁盘缓存或备份；用户主动使用的搜索关键词以最多 10 条历史保存在应用私有存储，可在搜索页清空；SavedState 只保存随机 token，进程消失后重新定位，失败显示对应原因的空态；首页路由收到失效会话时显示空态，并可重新定位。天气 UI 不再显示所选地点标签、预报时间和来源页脚。

## 证据对应

| 规格／场景 | 自动化证据 | 限制 |
| --- | --- | --- |
| location-search：trim、防抖、显式选择、清空、旧请求 | SearchViewModelTest；OpenMeteoRepositoryTest；LocationQueriesTest | 供应商所有县区覆盖不由合成数据证明 |
| location-search：启动定位／天气成功与失败、权限设置恢复、切换选择与取消恢复、路由会话替换、旋转、无额外天气页 | HomeViewModelTest；HomeFlowTest；OpenMeteoHttpTest | Fake 坐标与天气验证状态分支，不代替真实设备定位覆盖；目标 API 矩阵仍单独追踪 |
| location-search：重名、点击、旋转、错误／空 | WeatherFlowTest 搜索两用例；FoundationUiTest 路由 | 设备版本执行结果见 build-validation |
| immersive-startup：透明系统栏、刘海／键盘避让、重建后不重复加边距、真实加载阶段、旋转不重复请求 | ImmersiveStartupTest | API 35 模拟器，使用可控定位／天气响应 |
| current-weather：零值、65%、占位、高低温、失败保留数据 | OpenMeteoMapperTest；WeatherFlowTest 数值用例 | 测试天气为合成数据 |
| current-weather：局部成功、重试、保留原数据、限流 | WeatherViewModelTest；RequestExecutorTest | 实际公共端点不会人为耗尽配额 |
| seven-day-forecast：D…D+6、跨时区、DST、午夜、冲突日 | ForecastWindowTest；WeatherViewModelTest 午夜用例 | 不替代供应商真实午夜连续响应 |
| seven-day-forecast：7 行、缺今天、缺日提示、单概况 | WeatherFlowTest 数值用例；DailyForecastAdapter/XML 检查 | 小时预报为独立卡片 |
| hourly-forecast：当前小时至未来 23 小时、跨天／DST、缺失占位、整点刷新、单独重试 | HourlyForecastWindowTest；OpenMeteoMapperTest；OpenMeteoHttpTest；WeatherViewModelTest | 固定 Clock 与合成响应验证边界 |
| hourly-forecast：卡片顺序、24 项、横向滑动至最后一小时、旋转复用 | WeatherFlowTest 小时预报用例 | 模拟器上的合成数据，不代替所有设备尺寸验证 |
| system-localization：三语、首选、脚本、英文回退 | LocaleResolverTest（11）；FoundationUiTest；WeatherFormatterTest；三语／fr-FR 的 HomeActivity／WeatherFragment 设备场景 | 截图使用测试进程语言快照，不等于所有系统版本真实设置变更 |
| system-localization：迟到译名／天气、离线保留、恢复 | SearchViewModelTest、WeatherViewModelTest 语言代次 | 系统配置重建仍需目标设备矩阵 |
| weather-data-access：公共 HTTPS、无 Key、编码、取消、截止、错误分类 | RequestExecutorTest；OpenMeteoHttpTest；OpenMeteoRepositoryTest | 真实网络区域独立记录 |

任务按实现和已执行证据勾选。大陆宽带／移动／海外性能、真实午夜连续响应等外部门槛未完成前，不将全部规格宣布验收通过。
