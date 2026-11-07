package com.example.skymoment.core.service

import com.example.skymoment.domain.HomeDependencies
import com.example.skymoment.domain.WeatherDependencies

/** 宿主装配契约；各功能页面通过自身所需的依赖视图解析同一服务。 */
interface IApplicationService : HomeDependencies, WeatherDependencies
