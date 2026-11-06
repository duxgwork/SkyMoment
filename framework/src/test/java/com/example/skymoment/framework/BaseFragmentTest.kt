package com.example.skymoment.framework

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import org.robolectric.Shadows.shadowOf
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BaseFragmentTest {
    @Test fun bindingIsRecreatedAfterDetachAndUnavailableOutsideViewLifecycle() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val fragment = ProbeFragment()
        assertThrows(IllegalStateException::class.java) { fragment.boundView() }
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fragment.subscriptions)
        val first = fragment.boundView()
        assertEquals(1, fragment.initializations)
        activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fragment.cancellations)
        assertEquals(1, fragment.clears)
        assertTrue(fragment.bindingAvailableDuringClear)
        assertThrows(IllegalStateException::class.java) { fragment.boundView() }
        activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fragment.subscriptions)
        assertNotSame(first, fragment.boundView())
        assertEquals(2, fragment.initializations)
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, fragment.cancellations)
        assertEquals(2, fragment.clears)
        assertThrows(IllegalStateException::class.java) { fragment.boundView() }
    }
}

class ProbeBinding(private val view: View) : ViewBinding { override fun getRoot(): View = view }
class ProbeFragment : BaseFragment<ProbeBinding>({ inflater, _, _ -> ProbeBinding(FrameLayout(inflater.context)) }) {
    var subscriptions = 0
    var cancellations = 0
    override suspend fun CoroutineScope.observeData() {
        subscriptions++
        try { awaitCancellation() } finally { cancellations++ }
    }
    var initializations = 0
    var clears = 0
    var bindingAvailableDuringClear = false
    fun boundView() = binding.root
    override fun initBinding(binding: ProbeBinding, savedInstanceState: Bundle?) { initializations++ }
    override fun clearBinding() { clears++; bindingAvailableDuringClear = binding.root === view }
}
