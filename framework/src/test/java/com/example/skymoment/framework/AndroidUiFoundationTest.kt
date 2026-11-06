package com.example.skymoment.framework

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUiFoundationTest {
    @Test fun localeWrapperUsesEffectiveLocaleWithoutMutatingBaseConfiguration() {
        val base = RuntimeEnvironment.getApplication()
        val original = Configuration(base.resources.configuration)
        val wrapped = LocaleContext.wrap(base, listOf(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-Hant", wrapped.resources.configuration.locales[0].toLanguageTag())
        assertEquals(original, base.resources.configuration)
        val fallback = LocaleContext.wrap(base, listOf(Locale.FRENCH, Locale.CHINESE))
        assertEquals("en", fallback.resources.configuration.locales[0].toLanguageTag())
    }

    @Test fun statusPanelReplacesRetryListenerAndTracksVisibility() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SkyMoment_Home)
        val panel = StatusPanel(context)
        val retry = panel.findViewById<View>(R.id.status_retry)
        val progress = panel.findViewById<View>(R.id.status_progress)
        var old = 0; var new = 0
        panel.render(StatusKind.ERROR, android.R.string.cancel) { old++ }
        retry.performClick()
        assertEquals(1, old)
        panel.render(StatusKind.EMPTY, android.R.string.ok) { new++ }
        retry.performClick()
        assertEquals(1, old); assertEquals(1, new)
        panel.render(StatusKind.LOADING, android.R.string.ok)
        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(View.GONE, retry.visibility)
        assertFalse(retry.hasOnClickListeners())
        panel.render(StatusKind.ERROR, android.R.string.cancel, retryEnabled = false) { new++ }
        assertFalse(retry.isEnabled)
        panel.setMessage("custom")
        assertEquals("custom", panel.findViewById<TextView>(R.id.status_message).text.toString())
        panel.render(StatusKind.HIDDEN, android.R.string.ok)
        assertEquals(View.GONE, panel.visibility)
    }
}
