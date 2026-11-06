package com.example.skymoment.search

import android.content.Context
import android.content.Intent
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.LocationSearchContract
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.ILocationSearchService

@Route(path = ServiceRoutes.LOCATION_SEARCH)
class LocationSearchService : ILocationSearchService, IProvider {
    override fun init(context: Context) = Unit
    override fun createSelectionIntent(context: Context): Intent =
        Intent(context, SearchActivity::class.java).putExtra(LocationSearchContract.RETURN_SELECTION, true)
}
