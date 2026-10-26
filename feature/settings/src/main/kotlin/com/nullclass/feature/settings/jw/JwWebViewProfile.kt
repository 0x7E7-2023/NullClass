package com.nullclass.feature.settings.jw

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal fun timetableProfileName(timetableId: String, defaultProfileOwnerId: String): String =
    if (timetableId == defaultProfileOwnerId) Profile.DEFAULT_PROFILE_NAME else "jw_timetable_$timetableId"

internal fun isJwProfileSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

internal fun WebView.useJwProfile(profileName: String) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
    ProfileStore.getInstance().getOrCreateProfile(profileName)
    WebViewCompat.setProfile(this, profileName)
}

internal fun WebView.jwCookieManager(): CookieManager =
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        WebViewCompat.getProfile(this).cookieManager
    } else {
        CookieManager.getInstance()
    }
