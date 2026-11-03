package com.nullclass.feature.settings.jw

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal fun timetableProfileName(timetableId: String, defaultProfileOwnerId: String): String =
    if (timetableId == defaultProfileOwnerId) Profile.DEFAULT_PROFILE_NAME else "jw_timetable_$timetableId"

internal fun WebView.useJwProfile(profileName: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    ProfileStore.getInstance().getOrCreateProfile(profileName)
    WebViewCompat.setProfile(this, profileName)
}

/** 清空默认 Profile 的 cookie 与网页存储（旧 WebView 换课表时用）。 */
internal suspend fun clearDefaultJwProfile() = withContext(Dispatchers.Main) {
    WebStorage.getInstance().deleteAllData()
    val cookies = CookieManager.getInstance()
    suspendCancellableCoroutine<Unit> { cont -> cookies.removeAllCookies { cont.resume(Unit) } }
    cookies.flush()
}

internal fun WebView.jwCookieManager(): CookieManager =
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        WebViewCompat.getProfile(this).cookieManager
    } else {
        CookieManager.getInstance()
    }
