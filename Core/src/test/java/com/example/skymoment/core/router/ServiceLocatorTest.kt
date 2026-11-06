package com.example.skymoment.core.router

import android.content.Context
import android.content.Intent
import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.model.RouteMeta
import com.alibaba.android.arouter.facade.template.IProvider
import com.alibaba.android.arouter.launcher.ARouter
import com.example.skymoment.core.service.ILocationSearchService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceLocatorTest {
    @Before fun registerService() {
        ARouter.openDebug()
        ARouterNavigator.initialize(RuntimeEnvironment.getApplication())
        check(ARouter.getInstance().addRouteGroup { routes ->
            routes[ServiceRoutes.LOCATION_SEARCH] = RouteMeta.build(
                RouteType.PROVIDER, SelectionService::class.java, ServiceRoutes.LOCATION_SEARCH,
                "location", null, -1, Int.MIN_VALUE
            )
        })
    }
    @After fun releaseRouter() { ARouter.getInstance().destroy() }

    @Test fun resolvesTypedCachedServiceAndSelectionIntent() {
        val service = ServiceLocator.get(ServiceRoutes.LOCATION_SEARCH, ILocationSearchService::class.java)
        assertSame(service, ServiceLocator.get(ServiceRoutes.LOCATION_SEARCH, ILocationSearchService::class.java))
        val intent = LocationSearchContract().createIntent(RuntimeEnvironment.getApplication(), Unit)
        assertEquals("test.selection", intent.action)
        assertTrue(intent.getBooleanExtra(LocationSearchContract.RETURN_SELECTION, false))
    }

    @Test fun wrongContractAndMissingRouteFailImmediately() {
        val wrongType = assertThrows(IllegalStateException::class.java) {
            ServiceLocator.get(ServiceRoutes.LOCATION_SEARCH, Runnable::class.java)
        }
        assertTrue(wrongType.message!!.contains(ServiceRoutes.LOCATION_SEARCH))
        assertThrows(IllegalStateException::class.java) {
            ServiceLocator.get("/missing/service", ILocationSearchService::class.java)
        }
    }
}

class SelectionService : ILocationSearchService, IProvider {
    override fun init(context: Context) = Unit
    override fun createSelectionIntent(context: Context) =
        Intent("test.selection").putExtra(LocationSearchContract.RETURN_SELECTION, true)
}
