package com.example.skymoment.framework

import android.os.Bundle
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BaseActivityTest {
    @Test fun bindingLivesFromCreateThroughCleanupAndIsClearedOnDestroy() {
        val controller = Robolectric.buildActivity(ProbeActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SkyMoment_Home)
        assertThrows(IllegalStateException::class.java) { activity.boundView() }
        controller.setup()
        assertTrue(activity.initialized)
        assertNotNull(activity.boundView())
        controller.pause().stop().destroy()
        assertTrue(activity.cleanedWithBinding)
        assertThrows(IllegalStateException::class.java) { activity.boundView() }
    }
}

class ProbeActivity : BaseActivity<ProbeBinding>({ ProbeBinding(FrameLayout(it.context)) }) {
    var initialized = false
    var cleanedWithBinding = false
    fun boundView() = binding.root
    override fun initBinding(binding: ProbeBinding, savedInstanceState: Bundle?) { initialized = true }
    override fun clearBinding() { cleanedWithBinding = binding.root != null }
}
