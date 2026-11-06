package com.example.skymoment.weather

import android.content.Context
import androidx.fragment.app.Fragment
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.IWeatherService

@Route(path = ServiceRoutes.WEATHER)
class WeatherService : IWeatherService, IProvider {
    override fun init(context: Context) = Unit
    override fun createFragment(sessionToken: String): Fragment = WeatherFragment.forSession(sessionToken)
}
