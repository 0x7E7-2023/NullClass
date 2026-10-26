package com.nullclass.feature.settings.jw

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class JwWebViewProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val views = mutableListOf<WebView>()
    private val profileNames = mutableListOf<String>()

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            views.forEach { it.destroy() }
            views.clear()
            profileNames.forEach { ProfileStore.getInstance().deleteProfile(it) }
            profileNames.clear()
        }
    }

    @Test
    fun cookiesAreIsolatedAndRestoredWhenSwitchingBack() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val suffix = System.nanoTime()
        val profileA = "jw_test_a_$suffix"
        val profileB = "jw_test_b_$suffix"
        profileNames += listOf(profileA, profileB)
        val cookiesA = newCookieManager(profileA)
        val cookiesB = newCookieManager(profileB)

        setCookie(cookiesA, "session=A")
        assertNull(cookiesB.getCookie(URL))
        setCookie(cookiesB, "session=B")
        assertEquals("session=A", cookiesA.getCookie(URL))
        assertEquals("session=B", cookiesB.getCookie(URL))

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            views.forEach { it.destroy() }
            views.clear()
        }
        val restoredA = newCookieManager(profileA)
        assertEquals("session=A", restoredA.getCookie(URL))
    }

    private fun newCookieManager(profileName: String): CookieManager {
        lateinit var cookieManager: CookieManager
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = WebView(context)
            views += view
            view.useJwProfile(profileName)
            assertEquals(profileName, WebViewCompat.getProfile(view).name)
            cookieManager = view.jwCookieManager()
        }
        return cookieManager
    }

    private fun setCookie(cookieManager: CookieManager, value: String) {
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cookieManager.setCookie(URL, value) { completed.countDown() }
        }
        assertTrue(completed.await(10, TimeUnit.SECONDS))
    }

    private companion object {
        const val URL = "https://jw.example.com/"
    }
}
